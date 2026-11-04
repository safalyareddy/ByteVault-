package dev.bytevault.controlplane.repair;

import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Under-replication scanner. Creates one PENDING repair job per chunk whose VERIFIED replicas on
 * non-draining nodes number fewer than its object's replication factor. The partial unique index
 * {@code uq_repair_jobs_active_chunk} guarantees at most one active job per chunk even if two
 * planners race. A chunk whose job was abandoned is left alone for 10 minutes before a new one.
 */
@Component
public class RepairPlanner {

    private static final Logger log = LoggerFactory.getLogger(RepairPlanner.class);
    private static final int BATCH = 200;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;
    private final RepairProperties props;
    private final AtomicLong underReplicated = new AtomicLong();

    public RepairPlanner(JdbcClient jdbc, TransactionTemplate tx, OutboxWriter outbox, RepairProperties props,
                         MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.props = props;
        Gauge.builder("bv_under_replicated_chunks", underReplicated, AtomicLong::get)
                .description("Chunks with fewer VERIFIED replicas on non-draining nodes than their RF").register(meters);
    }

    private record Planned(UUID id, UUID chunkId, String reason) {}

    @Scheduled(initialDelay = 3_000, fixedDelayString = "${bytevault.repair.planner-interval:5s}")
    public void plan() {
        underReplicated.set(jdbc.sql("SELECT count(*) " + NEEDS_REPAIR).query(Long.class).single());
        if (!props.enabled()) {
            return;
        }
        List<Planned> created = tx.execute(s -> {
            List<Planned> jobs = jdbc.sql("""
                    INSERT INTO repair_jobs (chunk_id, reason, state, max_attempts)
                    SELECT c.id,
                           CASE WHEN EXISTS (SELECT 1 FROM chunk_replicas r WHERE r.chunk_id = c.id AND r.state = 'CORRUPT')
                                     THEN 'CORRUPTION'
                                WHEN EXISTS (SELECT 1 FROM chunk_replicas r WHERE r.chunk_id = c.id AND r.state = 'LOST')
                                     THEN 'NODE_OFFLINE'
                                WHEN EXISTS (SELECT 1 FROM chunk_replicas r JOIN storage_nodes n ON n.id = r.node_id
                                              WHERE r.chunk_id = c.id AND r.state = 'VERIFIED' AND n.state = 'DRAINING')
                                     THEN 'DRAIN'
                                ELSE 'MANUAL' END,
                           'PENDING', :max
                    """ + NEEDS_REPAIR + """
                       AND NOT EXISTS (SELECT 1 FROM repair_jobs j WHERE j.chunk_id = c.id
                                        AND (j.state NOT IN ('COMMITTED', 'ABANDONED')
                                             OR (j.state = 'ABANDONED' AND j.completed_at > now() - interval '10 minutes')))
                     LIMIT :lim
                    ON CONFLICT (chunk_id) WHERE state NOT IN ('COMMITTED', 'ABANDONED') DO NOTHING
                    RETURNING id, chunk_id, reason
                    """).param("max", props.maxAttempts()).param("lim", BATCH).query(Planned.class).list();
            for (Planned j : jobs) {
                outbox.append("REPAIR_JOB", j.id().toString(), 0, "RepairRequested", Topics.REPAIR_COMMANDS,
                        j.chunkId().toString(), Map.of("repairJobId", j.id(), "chunkId", j.chunkId(), "reason", j.reason()));
            }
            return jobs;
        });
        if (created != null && !created.isEmpty()) {
            log.info("Planned {} repair job(s)", created.size());
        }
    }

    static final String NEEDS_REPAIR = """
             FROM chunks c JOIN objects o ON o.id = c.object_id
            WHERE o.status IN ('COMPLETE', 'DEGRADED') AND c.status IN ('STORED', 'UNDER_REPLICATED')
              AND (SELECT count(*) FROM chunk_replicas r JOIN storage_nodes n ON n.id = r.node_id
                    WHERE r.chunk_id = c.id AND r.state = 'VERIFIED' AND n.state <> 'DRAINING') < o.replication_factor
            """;
}
