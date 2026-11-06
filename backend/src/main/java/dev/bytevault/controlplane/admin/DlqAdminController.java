package dev.bytevault.controlplane.admin;

import dev.bytevault.controlplane.audit.AuditWriter;
import dev.bytevault.controlplane.events.EventEnvelope;
import dev.bytevault.controlplane.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dead-letter inspection and guarded replay. Replay re-publishes the original value and key to the
 * original topic, preserving the event ID, so consumers still deduplicate. Every replay records
 * operator, reason and attempt number in the audit log.
 */
@RestController
@RequestMapping("/api/v1/admin/dlq")
public class DlqAdminController {

    private static final java.util.Set<String> REPLAYABLE_TOPICS = java.util.Set.of(
            "bytevault.object-events", "bytevault.chunk-events", "bytevault.node-events",
            "bytevault.replication-events", "bytevault.repair-commands", "bytevault.repair-results");

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final AuditWriter audit;
    private final AdminGuard guard;

    public DlqAdminController(JdbcClient jdbc, KafkaTemplate<String, String> kafka, AuditWriter audit,
                              AdminGuard guard) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.audit = audit;
        this.guard = guard;
    }

    public record DlqEntry(UUID id, UUID eventId, String eventType, String originalTopic, String originalKey,
                           Integer originalPartition, Long originalOffset, String consumerGroup, String failureReason,
                           Integer attempts, OffsetDateTime failedAt, String correlationId, String status,
                           int replayCount, OffsetDateTime lastReplayedAt, String lastReplayedBy) {}

    @GetMapping
    public List<DlqEntry> list(@RequestHeader(value = AdminGuard.HEADER, required = false) String token,
                               @RequestParam(defaultValue = "PENDING") String status,
                               @RequestParam(defaultValue = "50") int limit) {
        guard.requireAdmin(token);
        if (!status.equals("PENDING") && !status.equals("REPLAYED") && !status.equals("ALL")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "status must be PENDING, REPLAYED or ALL");
        }
        return jdbc.sql("""
                SELECT id, event_id, event_type, original_topic, original_key, original_partition, original_offset,
                       consumer_group, failure_reason, attempts, failed_at, correlation_id, status, replay_count,
                       last_replayed_at, last_replayed_by
                  FROM dlq_events WHERE (:s = 'ALL' OR status = :s) ORDER BY recorded_at DESC LIMIT :lim
                """).param("s", status).param("lim", Math.min(Math.max(limit, 1), 200))
                .query(DlqEntry.class).list();
    }

    public record ReplayRequest(@NotBlank @Size(max = 500) String reason) {}

    private record Stored(String originalTopic, String originalKey, String payload, String status, int replayCount,
                          UUID eventId, long version) {}

    @PostMapping("/{id}/replay")
    public Map<String, Object> replay(@RequestHeader(value = AdminGuard.HEADER, required = false) String token,
                                      @PathVariable UUID id, @Valid @RequestBody ReplayRequest request) {
        String actor = guard.requireAdmin(token);
        Stored entry = jdbc.sql("SELECT original_topic, original_key, payload, status, replay_count, event_id, version "
                        + "FROM dlq_events WHERE id = :id").param("id", id).query(Stored.class).optional()
                .orElseThrow(() -> ApiException.notFound("DLQ entry"));
        if (!REPLAYABLE_TOPICS.contains(entry.originalTopic())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REPLAYABLE",
                    "original topic " + entry.originalTopic() + " is not replayable");
        }
        if (entry.status().equals("REPLAYED")) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REPLAYED",
                    "entry was already replayed; a new failure creates a new DLQ entry");
        }
        int attempt = entry.replayCount() + 1;
        // Claim first (compare-and-set on version) so two operators cannot replay the same entry twice.
        int claimed = jdbc.sql("""
                UPDATE dlq_events SET status = 'REPLAYED', replay_count = :n, last_replayed_at = now(),
                                      last_replayed_by = :actor, version = version + 1
                 WHERE id = :id AND version = :v AND status = 'PENDING'
                """).param("n", attempt).param("actor", actor).param("id", id).param("v", entry.version()).update();
        if (claimed != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_REPLAY", "entry changed concurrently; retry");
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(entry.originalTopic(), entry.originalKey(),
                entry.payload());
        record.headers().add(EventEnvelope.HEADER_REPLAY_ATTEMPT,
                Integer.toString(attempt).getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventEnvelope.HEADER_REPLAYED_BY, actor.getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            jdbc.sql("UPDATE dlq_events SET status = 'PENDING', version = version + 1 WHERE id = :id")
                    .param("id", id).update();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REPLAY_PUBLISH_FAILED",
                    "could not publish to " + entry.originalTopic() + "; entry left PENDING");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dlqEntryId", id);
        details.put("eventId", entry.eventId());
        details.put("originalTopic", entry.originalTopic());
        details.put("replayAttempt", attempt);
        details.put("reason", request.reason());
        audit.record(actor, "DLQ_REPLAY", "DLQ_EVENT", id.toString(), details);
        return details;
    }
}
