package dev.bytevault.controlplane.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytevault.controlplane.outbox.OutboxPublisher;
import dev.bytevault.controlplane.web.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Event timeline and statistics. Both are eventually-consistent projections built by Kafka
 * consumers; {@code /stats} returns the projection next to the authoritative PostgreSQL counts
 * so any lag or divergence is visible rather than hidden.
 */
@RestController
@RequestMapping("/api/v1")
public class EventQueryController {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final OutboxPublisher outbox;

    public EventQueryController(JdbcClient jdbc, ObjectMapper json, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
    }

    public record TimelineEvent(UUID eventId, String eventType, String aggregateType, String aggregateId,
                                long aggregateVersion, String correlationId, OffsetDateTime occurredAt,
                                OffsetDateTime receivedAt, String topic, int kafkaPartition, long kafkaOffset,
                                JsonNode payload) {}

    private record Row(UUID eventId, String eventType, String aggregateType, String aggregateId, long aggregateVersion,
                       String correlationId, OffsetDateTime occurredAt, OffsetDateTime receivedAt, String topic,
                       int kafkaPartition, long kafkaOffset, String payload) {}

    @GetMapping("/events")
    public List<TimelineEvent> events(@RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(required = false) String aggregateId,
                                      @RequestParam(required = false) String correlationId) {
        if (limit < 1 || limit > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "limit must be between 1 and 500");
        }
        return jdbc.sql("""
                SELECT event_id, event_type, aggregate_type, aggregate_id, aggregate_version, correlation_id,
                       occurred_at, received_at, topic, kafka_partition, kafka_offset, payload::text AS payload
                  FROM event_log
                 WHERE (CAST(:aid AS text) IS NULL OR aggregate_id = :aid)
                   AND (CAST(:cid AS text) IS NULL OR correlation_id = :cid)
                 ORDER BY occurred_at DESC, event_id
                 LIMIT :lim
                """).param("aid", aggregateId).param("cid", correlationId).param("lim", limit)
                .query(Row.class).list().stream()
                .map(r -> new TimelineEvent(r.eventId(), r.eventType(), r.aggregateType(), r.aggregateId(),
                        r.aggregateVersion(), r.correlationId(), r.occurredAt(), r.receivedAt(), r.topic(),
                        r.kafkaPartition(), r.kafkaOffset(), readJson(r.payload())))
                .toList();
    }

    public record Counts(long storedObjects, long storedBytes, long degradedObjects, long deletedObjects) {}

    public record ProjectionCounts(long storedObjects, long storedBytes, long degradedObjects, long deletedObjects,
                                   long eventsApplied, OffsetDateTime lastEventAt) {}

    public record Stats(ProjectionCounts projection, Counts authoritative, boolean consistent, long outboxBacklog,
                        String note) {}

    @GetMapping("/stats")
    public Stats stats() {
        ProjectionCounts projection = jdbc.sql("SELECT stored_objects, stored_bytes, degraded_objects, deleted_objects, "
                + "events_applied, last_event_at FROM object_stats WHERE id = 1").query(ProjectionCounts.class).single();
        Counts authoritative = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status IN ('COMPLETE', 'DEGRADED')) AS stored_objects,
                       COALESCE(sum(total_size) FILTER (WHERE status IN ('COMPLETE', 'DEGRADED')), 0) AS stored_bytes,
                       count(*) FILTER (WHERE status = 'DEGRADED') AS degraded_objects,
                       count(*) FILTER (WHERE status = 'DELETED') AS deleted_objects
                  FROM objects
                """).query(Counts.class).single();
        boolean consistent = projection.storedObjects() == authoritative.storedObjects()
                && projection.storedBytes() == authoritative.storedBytes()
                && projection.degradedObjects() == authoritative.degradedObjects()
                && projection.deletedObjects() == authoritative.deletedObjects();
        return new Stats(projection, authoritative, consistent, outbox.backlog(),
                "projection is eventually consistent; it may lag while the outbox backlog or consumer lag is non-zero");
    }

    private JsonNode readJson(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            return json.getNodeFactory().textNode(s);
        }
    }
}
