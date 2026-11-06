package dev.bytevault.controlplane.node;

import dev.bytevault.controlplane.audit.AuditWriter;
import dev.bytevault.controlplane.config.BytevaultProperties;
import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import dev.bytevault.protocol.NodeProtocol;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pull-based liveness for configured nodes: polls each node's health endpoint every heartbeat
 * interval and drives JOINING/SUSPECT -> HEALTHY and HEALTHY -> SUSPECT.
 *
 * <p>Scope (Phase 2): SUSPECT only removes a node from new placements. OFFLINE, replica LOST
 * marking and repair are Phase 5, so this class never transitions a node to OFFLINE.
 */
@Component
public class NodeProbe {

    private static final Logger log = LoggerFactory.getLogger(NodeProbe.class);

    private final NodeClient client;
    private final NodeRepository nodes;
    private final OutboxWriter outbox;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final Duration suspectAfter;

    public NodeProbe(NodeClient client, NodeRepository nodes, OutboxWriter outbox, AuditWriter audit,
                     TransactionTemplate tx, BytevaultProperties props) {
        this.client = client;
        this.nodes = nodes;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = tx;
        this.suspectAfter = props.suspectAfter();
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${bytevault.heartbeat-interval}")
    public void probeAll() {
        client.configuredNodes().values().forEach(node -> {
            try {
                nodes.registerIfAbsent(node.id(), node.endpoint().toString(), NodeProtocol.VERSION);
                probe(node.id());
            } catch (RuntimeException e) {
                log.error("Probe cycle failed for node {}", node.id(), e);
            }
        });
    }

    void probe(String nodeId) {
        NodeClient.NodeHealth health = null;
        String failure = null;
        try {
            health = client.health(nodeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            failure = e.getClass().getSimpleName();
        }
        if (health != null && !nodeId.equals(health.nodeId())) {
            // Misconfiguration: never accept bytes or credit health for the wrong identity.
            failure = "identity mismatch (reported " + health.nodeId() + ")";
            health = null;
        }
        if (health != null && "UP".equals(health.status())) {
            NodeClient.NodeHealth h = health;
            nodes.recordHeartbeat(nodeId, h.protocolVersion(), h.capacityBytes(), h.usableBytes());
            NodeRepository.NodeRow row = nodes.find(nodeId).orElseThrow();
            switch (row.state()) {
                case JOINING, SUSPECT -> transition(row, NodeState.HEALTHY, "health check passed");
                case OFFLINE -> transition(row, NodeState.JOINING, "node reachable again");
                default -> { }
            }
            return;
        }
        String reason = failure != null ? failure : "node reports status " + health.status();
        NodeRepository.NodeRow row = nodes.find(nodeId).orElseThrow();
        if (row.state() == NodeState.HEALTHY && heartbeatOlderThan(row, suspectAfter)) {
            transition(row, NodeState.SUSPECT, reason);
        }
        log.debug("Node {} probe failed: {}", nodeId, reason);
    }

    private static boolean heartbeatOlderThan(NodeRepository.NodeRow row, Duration threshold) {
        OffsetDateTime last = row.lastHeartbeatAt();
        return last == null || last.isBefore(OffsetDateTime.now().minus(threshold));
    }

    private void transition(NodeRepository.NodeRow row, NodeState to, String reason) {
        tx.executeWithoutResult(status -> {
            if (!nodes.transition(row.id(), row.state(), to)) {
                return; // someone else moved it first; next probe re-evaluates
            }
            Map<String, Object> payload = Map.of("nodeId", row.id(), "from", row.state().name(), "to", to.name(),
                    "reason", reason);
            outbox.append("NODE", row.id(), row.version() + 1, "NodeStateChanged", Topics.NODE_EVENTS, row.id(), payload);
            audit.record(AuditWriter.SYSTEM, "NODE_STATE_CHANGED", "NODE", row.id(), payload);
        });
        log.info("Node {} {} -> {} ({})", row.id(), row.state(), to, reason);
    }
}
