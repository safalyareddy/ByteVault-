package dev.bytevault.controlplane.object;

import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.node.NodeClient;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import dev.bytevault.controlplane.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tombstone-first deletion. {@link #requestDelete} only marks metadata DELETING (idempotent); the
 * scheduled {@link #cleanUp} removes bytes from nodes, retrying until every replica is confirmed
 * gone, then marks the object DELETED. Unreachable nodes simply delay completion.
 */
@Service
public class DeletionService {

    private static final Logger log = LoggerFactory.getLogger(DeletionService.class);
    private static final int BATCH = 200;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;
    private final NodeClient nodeClient;

    public DeletionService(JdbcClient jdbc, TransactionTemplate tx, OutboxWriter outbox, NodeClient nodeClient) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.nodeClient = nodeClient;
    }

    public String requestDelete(String owner, UUID objectId, String actor) {
        return tx.execute(t -> {
            record Current(String status, long totalSize) {}
            Current current = jdbc.sql("SELECT status, total_size FROM objects WHERE id = :id AND owner_id = :o FOR UPDATE")
                    .param("id", objectId).param("o", owner).query(Current.class).optional()
                    .orElseThrow(() -> ApiException.notFound("object"));
            String status = current.status();
            if (status.equals("DELETING") || status.equals("DELETED")) {
                return status;
            }
            long version = jdbc.sql("UPDATE objects SET status = 'DELETING', updated_at = now(), version = version + 1 "
                    + "WHERE id = :id RETURNING version").param("id", objectId).query(Long.class).single();
            jdbc.sql("UPDATE upload_sessions SET status = 'ABORTED', updated_at = now(), version = version + 1 "
                    + "WHERE object_id = :id AND status = 'ACTIVE'").param("id", objectId).update();
            jdbc.sql("UPDATE chunks SET status = 'DELETING', updated_at = now(), version = version + 1 "
                    + "WHERE object_id = :id").param("id", objectId).update();
            jdbc.sql("""
                    UPDATE chunk_replicas SET state = 'DELETING', updated_at = now(), version = version + 1
                     WHERE chunk_id IN (SELECT id FROM chunks WHERE object_id = :id) AND state <> 'DELETED'
                    """).param("id", objectId).update();
            outbox.append("OBJECT", objectId.toString(), version, "ObjectDeleteRequested", Topics.OBJECT_EVENTS,
                    objectId.toString(), Map.of("objectId", objectId, "actor", actor, "previousStatus", status,
                            "totalSize", current.totalSize()));
            return "DELETING";
        });
    }

    private record PendingReplica(UUID chunkId, String nodeId) {}

    @Scheduled(initialDelay = 5_000, fixedDelayString = "${bytevault.deletion-interval:10s}")
    public void cleanUp() {
        List<PendingReplica> pending = jdbc.sql("""
                SELECT r.chunk_id, r.node_id FROM chunk_replicas r JOIN storage_nodes n ON n.id = r.node_id
                 WHERE r.state = 'DELETING' AND n.state IN ('HEALTHY', 'DRAINING', 'JOINING')
                 ORDER BY r.updated_at LIMIT :lim
                """).param("lim", BATCH).query(PendingReplica.class).list();
        for (PendingReplica r : pending) {
            if (nodeClient.deleteChunk(r.nodeId(), r.chunkId())) {
                jdbc.sql("UPDATE chunk_replicas SET state = 'DELETED', updated_at = now(), version = version + 1 "
                        + "WHERE chunk_id = :c AND node_id = :n AND state = 'DELETING'")
                        .param("c", r.chunkId()).param("n", r.nodeId()).update();
            }
        }
        List<UUID> finished = jdbc.sql("""
                SELECT o.id FROM objects o
                 WHERE o.status = 'DELETING'
                   AND NOT EXISTS (SELECT 1 FROM chunk_replicas r JOIN chunks c ON c.id = r.chunk_id
                                    WHERE c.object_id = o.id AND r.state <> 'DELETED')
                 LIMIT :lim
                """).param("lim", BATCH).query(UUID.class).list();
        for (UUID objectId : finished) {
            tx.executeWithoutResult(t -> {
                Long version = jdbc.sql("""
                        UPDATE objects SET status = 'DELETED', deleted_at = now(), updated_at = now(),
                                           version = version + 1
                         WHERE id = :id AND status = 'DELETING' RETURNING version
                        """).param("id", objectId).query(Long.class).optional().orElse(null);
                if (version == null) {
                    return;
                }
                jdbc.sql("UPDATE chunks SET status = 'DELETED', updated_at = now(), version = version + 1 "
                        + "WHERE object_id = :id").param("id", objectId).update();
                outbox.append("OBJECT", objectId.toString(), version, "ObjectDeleted", Topics.OBJECT_EVENTS,
                        objectId.toString(), Map.of("objectId", objectId));
            });
            log.info("Object {} deleted from all nodes", objectId);
        }
    }
}
