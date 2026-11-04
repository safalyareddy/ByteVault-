package dev.bytevault.controlplane.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Maintains the {@code object_stats} projection from object events. Counters are not idempotent
 * by themselves, which is exactly why this consumer demonstrates the dedupe guarantee: a duplicate
 * delivery would otherwise double-count.
 */
@Component
public class ObjectStatsConsumer {

    public static final String GROUP = "bv-object-stats";

    private final IdempotentProcessor processor;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ObjectStatsConsumer(IdempotentProcessor processor, JdbcClient jdbc, ObjectMapper json) {
        this.processor = processor;
        this.jdbc = jdbc;
        this.json = json;
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = Topics.OBJECT_EVENTS)
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.parse(json, record.value());
        processor.process(GROUP, event, this::apply);
    }

    private void apply(EventEnvelope e) {
        if (!processor.advanceVersion(GROUP, e.aggregateId(), e.aggregateVersion())) {
            return; // stale or reordered event for this object
        }
        JsonNode p = e.payload();
        switch (e.eventType()) {
            case "ObjectCompleted" -> update("stored_objects = stored_objects + 1, "
                    + "stored_bytes = stored_bytes + :size", requireLong(p, "totalSize"));
            case "ObjectDegraded" -> update("degraded_objects = degraded_objects + 1", 0);
            case "ObjectRestored" -> update("degraded_objects = GREATEST(degraded_objects - 1, 0)", 0);
            case "ObjectDeleteRequested" -> {
                String previous = p.path("previousStatus").asText();
                if (previous.equals("COMPLETE") || previous.equals("DEGRADED")) {
                    update("stored_objects = stored_objects - 1, stored_bytes = stored_bytes - :size"
                            + (previous.equals("DEGRADED") ? ", degraded_objects = GREATEST(degraded_objects - 1, 0)" : ""),
                            requireLong(p, "totalSize"));
                } else {
                    update("", 0);
                }
            }
            case "ObjectDeleted" -> update("deleted_objects = deleted_objects + 1", 0);
            default -> update("", 0);
        }
    }

    private void update(String set, long size) {
        jdbc.sql("UPDATE object_stats SET " + (set.isEmpty() ? "" : set + ", ") + "events_applied = events_applied + 1, "
                        + "last_event_at = now(), updated_at = now() WHERE id = 1")
                .param("size", size).update();
    }

    private static long requireLong(JsonNode payload, String field) {
        JsonNode n = payload.get(field);
        if (n == null || !n.canConvertToLong()) {
            throw new InvalidEventException("payload field " + field + " missing or not a number");
        }
        return n.asLong();
    }
}
