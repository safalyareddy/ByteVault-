package dev.bytevault.controlplane.object;

import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a replica that failed verification on read (CORRUPT) or is missing on its node (LOST).
 * The chunk becomes UNDER_REPLICATED and a COMPLETE object becomes DEGRADED, all in one
 * transaction with the outbox event. Re-replication is the repair engine's job (Phase 5).
 */
@Component
public class ReplicaFaultRecorder {

    private static final Logger log = LoggerFactory.getLogger(ReplicaFaultRecorder.class);

    public enum Fault { CORRUPT, LOST }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;

    public ReplicaFaultRecorder(JdbcClient jdbc, TransactionTemplate tx, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
    }

    public void record(UUID objectId, UUID chunkId, String nodeId, Fault fault, String expectedSha, String actualSha,
                       String detail) {
        tx.executeWithoutResult(t -> {
            Long replicaVersion = jdbc.sql("""
                    UPDATE chunk_replicas
                       SET state = :fault, last_error = :detail, updated_at = now(), version = version + 1
                     WHERE chunk_id = :c AND node_id = :n AND state = 'VERIFIED'
                    RETURNING version
                    """).param("fault", fault.name()).param("detail", detail).param("c", chunkId).param("n", nodeId)
                    .query(Long.class).optional().orElse(null);
            if (replicaVersion == null) {
                return; // already recorded by a concurrent reader
            }
            jdbc.sql("UPDATE chunks SET status = 'UNDER_REPLICATED', updated_at = now(), version = version + 1 "
                    + "WHERE id = :c AND status = 'STORED'").param("c", chunkId).update();
            Long objectVersion = jdbc.sql("""
                    UPDATE objects SET status = 'DEGRADED', updated_at = now(), version = version + 1
                     WHERE id = :o AND status = 'COMPLETE' RETURNING version
                    """).param("o", objectId).query(Long.class).optional().orElse(null);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("objectId", objectId);
            payload.put("chunkId", chunkId);
            payload.put("nodeId", nodeId);
            payload.put("expectedSha256", expectedSha);
            payload.put("actualSha256", actualSha);
            payload.put("detail", detail);
            outbox.append("REPLICA", chunkId + "@" + nodeId, replicaVersion,
                    fault == Fault.CORRUPT ? "ReplicaCorrupt" : "ReplicaLost", Topics.REPLICATION_EVENTS,
                    chunkId.toString(), payload);
            if (objectVersion != null) {
                outbox.append("OBJECT", objectId.toString(), objectVersion, "ObjectDegraded", Topics.OBJECT_EVENTS,
                        objectId.toString(), Map.of("objectId", objectId, "chunkId", chunkId, "reason", fault.name()));
            }
        });
        log.warn("Replica {} on node {} for chunk {} of object {}: {}", fault, nodeId, chunkId, objectId, detail);
    }
}
