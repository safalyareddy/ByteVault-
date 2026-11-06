package dev.bytevault.controlplane.repair;

import dev.bytevault.controlplane.audit.AuditWriter;
import dev.bytevault.controlplane.node.NodeClient;
import dev.bytevault.controlplane.object.ReplicaFaultRecorder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Compares what each reachable node actually stores with what metadata says it stores, for use
 * after a PostgreSQL restore (docs/operations-runbook.md):
 * <ul>
 *   <li><b>orphans</b>: chunk files with no replica row at all (written after the backup was
 *       taken). With {@code apply}, deleted from the node.</li>
 *   <li><b>missing</b>: VERIFIED replica rows whose file is gone (deleted after the backup). With
 *       {@code apply}, marked LOST so repair restores them from another verified copy.</li>
 * </ul>
 * A dry run changes nothing. Every run is audited. Nodes that are not HEALTHY or DRAINING are
 * skipped and reported, never treated as empty.
 */
@Service
public class InventoryReconciler {

    private static final int SAMPLE = 20;

    private final JdbcClient jdbc;
    private final NodeClient nodeClient;
    private final ReplicaFaultRecorder faults;
    private final AuditWriter audit;

    public InventoryReconciler(JdbcClient jdbc, NodeClient nodeClient, ReplicaFaultRecorder faults, AuditWriter audit) {
        this.jdbc = jdbc;
        this.nodeClient = nodeClient;
        this.faults = faults;
        this.audit = audit;
    }

    public record NodeReport(String nodeId, String status, int chunksOnNode, int replicaRows, int orphans,
                             List<String> orphanSample, int missing, List<String> missingSample, int orphansDeleted,
                             int missingMarkedLost) {}

    public record Report(boolean applied, List<NodeReport> nodes) {}

    private record Row(UUID chunkId, String state, UUID objectId, String sha256) {}

    public Report run(boolean apply, String actor) {
        List<NodeReport> reports = new ArrayList<>();
        List<Map<String, Object>> nodes = jdbc.sql("SELECT id, state FROM storage_nodes ORDER BY id").query().listOfRows();
        for (Map<String, Object> n : nodes) {
            String nodeId = (String) n.get("id");
            String state = (String) n.get("state");
            if (!state.equals("HEALTHY") && !state.equals("DRAINING")) {
                reports.add(new NodeReport(nodeId, "SKIPPED_" + state, 0, 0, 0, List.of(), 0, List.of(), 0, 0));
                continue;
            }
            Set<UUID> onNode;
            try {
                onNode = nodeClient.listChunks(nodeId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                reports.add(new NodeReport(nodeId, "UNREACHABLE", 0, 0, 0, List.of(), 0, List.of(), 0, 0));
                continue;
            }
            Map<UUID, Row> rows = new HashMap<>();
            jdbc.sql("""
                    SELECT r.chunk_id, r.state, c.object_id, c.sha256 FROM chunk_replicas r JOIN chunks c ON c.id = r.chunk_id
                     WHERE r.node_id = :n AND r.state <> 'DELETED'
                    """).param("n", nodeId).query(Row.class).list().forEach(r -> rows.put(r.chunkId(), r));

            List<UUID> orphans = onNode.stream().filter(id -> !rows.containsKey(id)).sorted().toList();
            List<Row> missing = rows.values().stream()
                    .filter(r -> r.state().equals("VERIFIED") && !onNode.contains(r.chunkId()))
                    .sorted((a, b) -> a.chunkId().compareTo(b.chunkId())).toList();
            int deleted = 0;
            int markedLost = 0;
            if (apply) {
                for (UUID orphan : orphans) {
                    // Re-check right before deleting: a replica row may have appeared since listing.
                    boolean nowKnown = jdbc.sql("SELECT EXISTS (SELECT 1 FROM chunk_replicas WHERE chunk_id = :c "
                            + "AND node_id = :n)").param("c", orphan).param("n", nodeId).query(Boolean.class).single();
                    if (!nowKnown && nodeClient.deleteChunk(nodeId, orphan)) {
                        deleted++;
                    }
                }
                for (Row m : missing) {
                    faults.record(m.objectId(), m.chunkId(), nodeId, ReplicaFaultRecorder.Fault.LOST, m.sha256(), null,
                            "reconciliation: file missing on node");
                    markedLost++;
                }
            }
            reports.add(new NodeReport(nodeId, "CHECKED", onNode.size(), rows.size(), orphans.size(),
                    orphans.stream().limit(SAMPLE).map(UUID::toString).toList(), missing.size(),
                    missing.stream().limit(SAMPLE).map(r -> r.chunkId().toString()).toList(), deleted, markedLost));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("apply", apply);
        details.put("orphans", reports.stream().mapToInt(NodeReport::orphans).sum());
        details.put("missing", reports.stream().mapToInt(NodeReport::missing).sum());
        details.put("orphansDeleted", reports.stream().mapToInt(NodeReport::orphansDeleted).sum());
        details.put("missingMarkedLost", reports.stream().mapToInt(NodeReport::missingMarkedLost).sum());
        audit.record(actor, "INVENTORY_RECONCILE", "CLUSTER", "inventory", details);
        return new Report(apply, reports);
    }
}
