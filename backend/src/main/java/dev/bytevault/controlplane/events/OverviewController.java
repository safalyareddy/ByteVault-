package dev.bytevault.controlplane.events;

import dev.bytevault.controlplane.outbox.OutboxPublisher;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator overview (ADMIN). Every number is read from PostgreSQL at request time, except the
 * {@code projection} block, which is the Kafka-built statistics read model and is labelled so.
 */
@RestController
public class OverviewController {

    private final JdbcClient jdbc;
    private final OutboxPublisher outbox;

    public OverviewController(JdbcClient jdbc, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record Objects(long stored, long storedBytes, long degraded, long uploading, long deleting) {}

    public record Chunks(long total, long underReplicated) {}

    public record Repairs(long active, long waitingForCapacity, long committedLastHour, long abandoned) {}

    public record Events(long outboxBacklog, long dlqPending, OffsetDateTime lastEventAt) {}

    public record Overview(OffsetDateTime generatedAt, Map<String, Long> nodesByState, Objects objects, Chunks chunks,
                           Repairs repairs, Events events) {}

    @GetMapping("/api/v1/overview")
    public Overview overview() {
        Map<String, Long> nodes = new LinkedHashMap<>();
        for (String s : new String[] {"HEALTHY", "SUSPECT", "OFFLINE", "JOINING", "DRAINING"}) {
            nodes.put(s, 0L);
        }
        jdbc.sql("SELECT state, count(*) AS n FROM storage_nodes GROUP BY state").query()
                .listOfRows().forEach(r -> nodes.put((String) r.get("state"), ((Number) r.get("n")).longValue()));
        Objects objects = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status IN ('COMPLETE', 'DEGRADED')) AS stored,
                       COALESCE(sum(total_size) FILTER (WHERE status IN ('COMPLETE', 'DEGRADED')), 0) AS stored_bytes,
                       count(*) FILTER (WHERE status = 'DEGRADED') AS degraded,
                       count(*) FILTER (WHERE status IN ('INITIATED', 'UPLOADING')) AS uploading,
                       count(*) FILTER (WHERE status = 'DELETING') AS deleting
                  FROM objects
                """).query(Objects.class).single();
        Chunks chunks = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status IN ('STORED', 'UNDER_REPLICATED')) AS total,
                       count(*) FILTER (WHERE status = 'UNDER_REPLICATED') AS under_replicated
                  FROM chunks
                """).query(Chunks.class).single();
        Repairs repairs = jdbc.sql("""
                SELECT count(*) FILTER (WHERE state IN ('PENDING', 'CLAIMED', 'COPYING', 'VERIFYING')) AS active,
                       count(*) FILTER (WHERE state = 'PENDING' AND last_error LIKE 'waiting for capacity%')
                           AS waiting_for_capacity,
                       count(*) FILTER (WHERE state = 'COMMITTED' AND target_node_id IS NOT NULL
                                          AND completed_at > now() - interval '1 hour') AS committed_last_hour,
                       count(*) FILTER (WHERE state = 'ABANDONED') AS abandoned
                  FROM repair_jobs
                """).query(Repairs.class).single();
        long dlq = jdbc.sql("SELECT count(*) FROM dlq_events WHERE status = 'PENDING'").query(Long.class).single();
        OffsetDateTime lastEvent = jdbc.sql("SELECT max(received_at) FROM event_log").query(OffsetDateTime.class)
                .optional().orElse(null);
        return new Overview(OffsetDateTime.now(), nodes, objects, chunks, repairs,
                new Events(outbox.backlog(), dlq, lastEvent));
    }
}
