package dev.bytevault.controlplane.repair;

import dev.bytevault.controlplane.node.NodeClient;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciles LOST replicas on nodes that are HEALTHY again (after an OFFLINE period, or after a
 * read found a chunk missing). The node re-hashes each chunk: if the bytes are intact and the chunk
 * is still short of RF, the replica is VERIFIED again (LOST -> VERIFIED); otherwise it is excess or
 * damaged and scheduled for deletion. Never creates more than RF VERIFIED replicas.
 */
@Component
public class RejoinReconciler {

    private static final Logger log = LoggerFactory.getLogger(RejoinReconciler.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final NodeClient nodeClient;
    private final ReplicationStatus status;
    private final RepairProperties props;

    public RejoinReconciler(JdbcClient jdbc, TransactionTemplate tx, NodeClient nodeClient, ReplicationStatus status,
                            RepairProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.nodeClient = nodeClient;
        this.status = status;
        this.props = props;
    }

    private record LostReplica(UUID chunkId, String nodeId, String sha256, int byteSize, int replicationFactor) {}

    @Scheduled(initialDelay = 5_000, fixedDelayString = "${bytevault.repair.reconcile-interval:5s}")
    public void reconcile() {
        if (!props.enabled()) {
            return;
        }
        List<LostReplica> lost = jdbc.sql("""
                SELECT r.chunk_id, r.node_id, c.sha256, c.byte_size, o.replication_factor
                  FROM chunk_replicas r JOIN chunks c ON c.id = r.chunk_id JOIN objects o ON o.id = c.object_id
                  JOIN storage_nodes n ON n.id = r.node_id
                 WHERE r.state = 'LOST' AND n.state = 'HEALTHY' AND o.status IN ('COMPLETE', 'DEGRADED')
                 LIMIT 200
                """).query(LostReplica.class).list();
        int restored = 0, retired = 0;
        for (LostReplica r : lost) {
            NodeClient.VerifyResult v = nodeClient.verifyChunk(r.nodeId(), r.chunkId());
            if (v.status() == NodeClient.FetchStatus.UNAVAILABLE) {
                continue;
            }
            boolean intact = v.matches(r.sha256(), r.byteSize());
            Boolean revived = tx.execute(t -> {
                int verified = jdbc.sql("SELECT count(*) FROM chunk_replicas WHERE chunk_id = :c AND state = 'VERIFIED'")
                        .param("c", r.chunkId()).query(Integer.class).single();
                boolean revive = intact && verified < r.replicationFactor();
                jdbc.sql("""
                        UPDATE chunk_replicas
                           SET state = :s, last_error = :err, updated_at = now(), version = version + 1,
                               last_verified_at = CASE WHEN :s = 'VERIFIED' THEN now() ELSE last_verified_at END
                         WHERE chunk_id = :c AND node_id = :n AND state = 'LOST'
                        """).param("s", revive ? "VERIFIED" : "DELETING")
                        .param("err", revive ? null : intact ? "excess after rejoin" : "missing or damaged after rejoin")
                        .param("c", r.chunkId()).param("n", r.nodeId()).update();
                status.refresh(r.chunkId());
                return revive;
            });
            if (Boolean.TRUE.equals(revived)) {
                restored++;
            } else {
                retired++;
            }
        }
        if (restored + retired > 0) {
            log.info("Reconciled LOST replicas: {} re-verified, {} scheduled for deletion", restored, retired);
        }
        // Copies on DRAINING nodes whose chunk is already fully replicated elsewhere (no repair job
        // will be planned for them, so retire them here).
        List<UUID> drained = jdbc.sql("""
                SELECT DISTINCT r.chunk_id FROM chunk_replicas r JOIN storage_nodes n ON n.id = r.node_id
                 WHERE n.state = 'DRAINING' AND r.state = 'VERIFIED' LIMIT 200
                """).query(UUID.class).list();
        for (UUID chunkId : drained) {
            tx.executeWithoutResult(t -> status.retireSuperseded(chunkId));
        }
        Integer healed = tx.execute(t -> status.restoreHealedObjects());
        if (healed != null && healed > 0) {
            log.warn("Safety sweep restored {} object(s) left DEGRADED with no under-replicated chunk", healed);
        }
    }
}
