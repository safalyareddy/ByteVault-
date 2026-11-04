package dev.bytevault.controlplane.node;

import dev.bytevault.controlplane.audit.AuditWriter;
import dev.bytevault.controlplane.config.ClusterProperties;
import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import dev.bytevault.protocol.Heartbeat;
import dev.bytevault.protocol.NodeProtocol;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies {@link NodeLifecycle} decisions: heartbeats, admission, and audited state changes. */
@Service
public class NodeLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(NodeLifecycleService.class);

    private final NodeRepository nodes;
    private final NodeClient nodeClient;
    private final OutboxWriter outbox;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final int admissionHeartbeats;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final dev.bytevault.controlplane.repair.ReplicationStatus replicationStatus;
    /** Consecutive UP heartbeats per node; in memory by design (ADR-0010). */
    private final Map<String, Integer> upStreak = new ConcurrentHashMap<>();

    public NodeLifecycleService(NodeRepository nodes, NodeClient nodeClient, OutboxWriter outbox, AuditWriter audit,
                                TransactionTemplate tx, ClusterProperties cluster,
                                org.springframework.jdbc.core.simple.JdbcClient jdbc,
                                dev.bytevault.controlplane.repair.ReplicationStatus replicationStatus) {
        this.jdbc = jdbc;
        this.replicationStatus = replicationStatus;
        this.nodes = nodes;
        this.nodeClient = nodeClient;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = tx;
        this.admissionHeartbeats = cluster.getAdmissionHeartbeats();
    }

    /** Handles an authenticated heartbeat and returns the node's resulting state. */
    public NodeState onHeartbeat(Heartbeat hb) {
        String id = hb.nodeId();
        String configuredEndpoint = nodeClient.configuredNodes().get(id).endpoint().toString();
        nodes.registerIfAbsent(id, configuredEndpoint, hb.protocolVersion());
        if (hb.advertisedEndpoint() != null && !hb.advertisedEndpoint().equals(configuredEndpoint)) {
            log.debug("Node {} advertises {}, control plane uses configured {}", id, hb.advertisedEndpoint(),
                    configuredEndpoint);
        }
        nodes.recordHeartbeat(id, hb.protocolVersion(), hb.capacityBytes(), hb.usableBytes());

        boolean up = "UP".equals(hb.status()) && hb.dataDirWritable() && hb.protocolVersion() == NodeProtocol.VERSION;
        int streak = up ? upStreak.merge(id, 1, Integer::sum) : 0;
        if (!up) {
            upStreak.remove(id);
        }
        NodeRepository.NodeRow row = nodes.find(id).orElseThrow();
        switch (NodeLifecycle.onHeartbeat(row.state(), up, streak, admissionHeartbeats)) {
            case RUN_ADMISSION_PROBE -> admit(row, streak);
            case MARK_HEALTHY -> transition(row, NodeState.HEALTHY, "heartbeat resumed");
            case MARK_SUSPECT -> transition(row, NodeState.SUSPECT, "node reports status " + hb.status()
                    + (hb.dataDirWritable() ? "" : ", data directory not writable"));
            case MARK_JOINING -> transition(row, NodeState.JOINING, "heartbeat after OFFLINE; re-admission required");
            case NONE -> { }
        }
        return nodes.find(id).orElseThrow().state();
    }

    /**
     * SUSPECT -> OFFLINE (Phase 5). In the same transaction every VERIFIED replica on the node
     * becomes LOST, affected chunks UNDER_REPLICATED and their objects DEGRADED, so the repair
     * planner sees the deficit immediately. Bytes on the node are not touched: if it returns, the
     * reconciler re-verifies them.
     */
    public boolean markOffline(NodeRepository.NodeRow row, String reason) {
        NodeLifecycle.requireAllowed(row.state(), NodeState.OFFLINE);
        Integer lost = tx.execute(status -> {
            if (!nodes.transition(row.id(), row.state(), NodeState.OFFLINE)) {
                return null;
            }
            record Lost(java.util.UUID chunkId, java.util.UUID objectId) {}
            java.util.List<Lost> replicas = jdbc.sql("""
                    UPDATE chunk_replicas r SET state = 'LOST', last_error = 'node offline', updated_at = now(),
                           version = r.version + 1
                      FROM chunks c
                     WHERE c.id = r.chunk_id AND r.node_id = :n AND r.state = 'VERIFIED'
                    RETURNING r.chunk_id, c.object_id
                    """).param("n", row.id()).query(Lost.class).list();
            for (Lost l : replicas) {
                outbox.append("REPLICA", l.chunkId() + "@" + row.id(), row.version() + 1, "ReplicaLost",
                        Topics.REPLICATION_EVENTS, l.chunkId().toString(),
                        Map.of("chunkId", l.chunkId(), "objectId", l.objectId(), "nodeId", row.id(),
                                "detail", "node offline"));
                replicationStatus.refresh(l.chunkId());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", row.id());
            payload.put("from", row.state().name());
            payload.put("to", NodeState.OFFLINE.name());
            payload.put("reason", reason);
            payload.put("replicasLost", replicas.size());
            outbox.append("NODE", row.id(), row.version() + 1, "NodeStateChanged", Topics.NODE_EVENTS, row.id(), payload);
            audit.record(AuditWriter.SYSTEM, "NODE_STATE_CHANGED", "NODE", row.id(), payload);
            return replicas.size();
        });
        if (lost != null) {
            log.warn("Node {} SUSPECT -> OFFLINE ({}); {} replica(s) marked LOST", row.id(), reason, lost);
        }
        return lost != null;
    }

    private void admit(NodeRepository.NodeRow row, int streak) {
        NodeClient.ProbeResult probe = nodeClient.admissionProbe(row.id());
        if (probe == NodeClient.ProbeResult.AUTHENTICATED) {
            transition(row, NodeState.HEALTHY, "admitted: " + streak + " consecutive UP heartbeats and an "
                    + "authenticated probe of the configured endpoint");
        } else {
            log.warn("Node {} not admitted: admission probe {}", row.id(), probe);
        }
    }

    /** Audited compare-and-set transition. Returns false if another writer changed the state first. */
    public boolean transition(NodeRepository.NodeRow row, NodeState to, String reason) {
        return transition(row, to, reason, AuditWriter.SYSTEM);
    }

    public boolean transition(NodeRepository.NodeRow row, NodeState to, String reason, String actor) {
        NodeLifecycle.requireAllowed(row.state(), to);
        Boolean changed = tx.execute(status -> {
            if (!nodes.transition(row.id(), row.state(), to)) {
                return false;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", row.id());
            payload.put("from", row.state().name());
            payload.put("to", to.name());
            payload.put("reason", reason);
            outbox.append("NODE", row.id(), row.version() + 1, "NodeStateChanged", Topics.NODE_EVENTS, row.id(),
                    payload);
            audit.record(actor, "NODE_STATE_CHANGED", "NODE", row.id(), payload);
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            log.info("Node {} {} -> {} ({})", row.id(), row.state(), to, reason);
            if (to != NodeState.HEALTHY) {
                upStreak.remove(row.id());
            }
        }
        return Boolean.TRUE.equals(changed);
    }
}
