package dev.bytevault.controlplane.node;

import dev.bytevault.controlplane.admin.AdminGuard;
import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import dev.bytevault.controlplane.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Graceful drain (admin, audited). DRAINING removes the node from new placements; the repair
 * planner copies its replicas elsewhere, and only after each copy is verified does the node's
 * replica get retired. {@code safeToRemove} becomes true once the node holds nothing that is
 * still needed. There is no degraded override in v1.
 */
@RestController
@RequestMapping("/api/v1/nodes/{nodeId}")
public class DrainController {

    private final NodeRepository nodes;
    private final NodeLifecycleService lifecycle;
    private final AdminGuard guard;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;

    public DrainController(NodeRepository nodes, NodeLifecycleService lifecycle, AdminGuard guard, OutboxWriter outbox,
                           TransactionTemplate tx, JdbcClient jdbc) {
        this.nodes = nodes;
        this.lifecycle = lifecycle;
        this.guard = guard;
        this.outbox = outbox;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    public record DrainRequest(@NotBlank @Size(max = 500) String reason) {}

    public record DrainStatus(String nodeId, String state, int verifiedReplicas, int replicasPendingDeletion,
                              int chunksStillDependingOnNode, boolean safeToRemove) {}

    @PostMapping("/drain")
    public DrainStatus drain(@RequestHeader(value = AdminGuard.HEADER, required = false) String token,
                             @PathVariable String nodeId, @Valid @RequestBody DrainRequest request) {
        String actor = guard.requireAdmin(token);
        NodeRepository.NodeRow row = nodes.find(nodeId).orElseThrow(() -> ApiException.notFound("node"));
        if (row.state() == NodeState.DRAINING) {
            return status(nodeId);
        }
        if (!NodeLifecycle.isAllowed(row.state(), NodeState.DRAINING)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                    "cannot drain a node in state " + row.state());
        }
        if (!lifecycle.transition(row, NodeState.DRAINING, "drain requested: " + request.reason(), actor)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_CHANGE", "node state changed concurrently; retry");
        }
        tx.executeWithoutResult(t -> outbox.append("NODE", nodeId, row.version() + 1, "NodeDrainRequested",
                Topics.NODE_EVENTS, nodeId, Map.of("nodeId", nodeId, "actor", actor, "reason", request.reason())));
        return status(nodeId);
    }

    @PostMapping("/drain/cancel")
    public DrainStatus cancel(@RequestHeader(value = AdminGuard.HEADER, required = false) String token,
                              @PathVariable String nodeId) {
        String actor = guard.requireAdmin(token);
        NodeRepository.NodeRow row = nodes.find(nodeId).orElseThrow(() -> ApiException.notFound("node"));
        if (row.state() != NodeState.DRAINING) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_DRAINING", "node is " + row.state());
        }
        lifecycle.transition(row, NodeState.HEALTHY, "drain cancelled", actor);
        return status(nodeId);
    }

    @GetMapping("/drain")
    public DrainStatus status(@PathVariable String nodeId) {
        NodeRepository.NodeRow row = nodes.find(nodeId).orElseThrow(() -> ApiException.notFound("node"));
        int verified = count("SELECT count(*) FROM chunk_replicas WHERE node_id = :n AND state = 'VERIFIED'", nodeId);
        int deleting = count("SELECT count(*) FROM chunk_replicas WHERE node_id = :n AND state = 'DELETING'", nodeId);
        int depending = count("""
                SELECT count(*) FROM chunk_replicas r JOIN chunks c ON c.id = r.chunk_id
                  JOIN objects o ON o.id = c.object_id
                 WHERE r.node_id = :n AND r.state = 'VERIFIED'
                   AND (SELECT count(*) FROM chunk_replicas x JOIN storage_nodes xn ON xn.id = x.node_id
                         WHERE x.chunk_id = c.id AND x.state = 'VERIFIED' AND xn.state <> 'DRAINING')
                       < o.replication_factor
                """, nodeId);
        return new DrainStatus(nodeId, row.state().name(), verified, deleting, depending,
                row.state() == NodeState.DRAINING && verified == 0 && deleting == 0);
    }

    private int count(String sql, String nodeId) {
        return jdbc.sql(sql).param("n", nodeId).query(Integer.class).single();
    }
}
