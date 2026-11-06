package dev.bytevault.controlplane.repair;

import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Recomputes a chunk's and its object's replication status from replica rows. Must run inside the
 * transaction that changed the replicas, so status and events commit with the change.
 *
 * <p>Chunk: STORED when it has at least RF VERIFIED replicas, else UNDER_REPLICATED.
 * Object: DEGRADED while any chunk is UNDER_REPLICATED, else COMPLETE (with ObjectRestored).
 */
@Component
public class ReplicationStatus {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public ReplicationStatus(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public void refresh(UUID chunkId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("refresh must run inside the replica-change transaction");
        }
        // Serialise status changes per object: without this lock, two repairs of different chunks
        // committing concurrently each still see the other chunk UNDER_REPLICATED, and the object
        // would stay DEGRADED although every chunk is healthy (found by SelfHealingIT).
        UUID owner = jdbc.sql("SELECT object_id FROM chunks WHERE id = :c").param("c", chunkId)
                .query(UUID.class).optional().orElse(null);
        if (owner == null) {
            return;
        }
        jdbc.sql("SELECT id FROM objects WHERE id = :o FOR UPDATE").param("o", owner).query(UUID.class).single();
        UUID objectId = jdbc.sql("""
                UPDATE chunks c
                   SET status = CASE WHEN (SELECT count(*) FROM chunk_replicas r
                                            WHERE r.chunk_id = c.id AND r.state = 'VERIFIED')
                                          >= (SELECT replication_factor FROM objects WHERE id = c.object_id)
                                     THEN 'STORED' ELSE 'UNDER_REPLICATED' END,
                       updated_at = now(), version = version + 1
                 WHERE c.id = :c AND c.status IN ('STORED', 'UNDER_REPLICATED')
                RETURNING c.object_id
                """).param("c", chunkId).query(UUID.class).optional().orElse(null);
        if (objectId == null) {
            return; // chunk is pending upload or being deleted: not this component's business
        }
        boolean anyUnder = jdbc.sql("SELECT EXISTS (SELECT 1 FROM chunks WHERE object_id = :o "
                + "AND status = 'UNDER_REPLICATED')").param("o", objectId).query(Boolean.class).single();
        if (anyUnder) {
            jdbc.sql("UPDATE objects SET status = 'DEGRADED', updated_at = now(), version = version + 1 "
                    + "WHERE id = :o AND status = 'COMPLETE' RETURNING version").param("o", objectId)
                    .query(Long.class).optional()
                    .ifPresent(v -> outbox.append("OBJECT", objectId.toString(), v, "ObjectDegraded",
                            Topics.OBJECT_EVENTS, objectId.toString(),
                            Map.of("objectId", objectId, "chunkId", chunkId, "reason", "UNDER_REPLICATED")));
        } else {
            jdbc.sql("UPDATE objects SET status = 'COMPLETE', updated_at = now(), version = version + 1 "
                    + "WHERE id = :o AND status = 'DEGRADED' RETURNING version").param("o", objectId)
                    .query(Long.class).optional()
                    .ifPresent(v -> outbox.append("OBJECT", objectId.toString(), v, "ObjectRestored",
                            Topics.OBJECT_EVENTS, objectId.toString(), Map.of("objectId", objectId)));
        }
    }

    /**
     * Restores objects left DEGRADED although none of their chunks is under-replicated any more.
     * {@link #refresh} prevents this under normal operation; this sweep is a safety net that makes
     * the invariant self-correcting. Returns how many objects were restored.
     */
    public int restoreHealedObjects() {
        List<UUID> healed = jdbc.sql("""
                SELECT o.id FROM objects o
                 WHERE o.status = 'DEGRADED'
                   AND NOT EXISTS (SELECT 1 FROM chunks c WHERE c.object_id = o.id AND c.status <> 'STORED')
                 LIMIT 100
                """).query(UUID.class).list();
        int restored = 0;
        for (UUID objectId : healed) {
            Long v = jdbc.sql("UPDATE objects SET status = 'COMPLETE', updated_at = now(), version = version + 1 "
                    + "WHERE id = :o AND status = 'DEGRADED' RETURNING version").param("o", objectId)
                    .query(Long.class).optional().orElse(null);
            if (v != null) {
                outbox.append("OBJECT", objectId.toString(), v, "ObjectRestored", Topics.OBJECT_EVENTS,
                        objectId.toString(), Map.of("objectId", objectId));
                restored++;
            }
        }
        return restored;
    }

    /**
     * Once a chunk has RF VERIFIED replicas on non-draining nodes, schedules removal of what is no
     * longer needed: CORRUPT and LOST replicas, and copies on DRAINING nodes. Never retires
     * anything while the chunk is short of RF, so the old copy survives until its replacement is
     * verified and committed.
     */
    public int retireSuperseded(UUID chunkId) {
        int active = jdbc.sql("""
                SELECT count(*) FROM chunk_replicas r JOIN storage_nodes n ON n.id = r.node_id
                 WHERE r.chunk_id = :c AND r.state = 'VERIFIED' AND n.state <> 'DRAINING'
                """).param("c", chunkId).query(Integer.class).single();
        int rf = jdbc.sql("SELECT o.replication_factor FROM chunks c JOIN objects o ON o.id = c.object_id WHERE c.id = :c")
                .param("c", chunkId).query(Integer.class).single();
        if (active < rf) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE chunk_replicas r SET state = 'DELETING', updated_at = now(), version = r.version + 1
                  FROM storage_nodes n
                 WHERE n.id = r.node_id AND r.chunk_id = :c
                   AND (r.state IN ('CORRUPT', 'LOST') OR (r.state = 'VERIFIED' AND n.state = 'DRAINING'))
                """).param("c", chunkId).update();
    }
}
