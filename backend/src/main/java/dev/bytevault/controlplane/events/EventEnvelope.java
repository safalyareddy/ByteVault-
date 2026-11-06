package dev.bytevault.controlplane.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

/** The JSON value of every ByteVault Kafka message (docs/kafka-events.md). */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        String correlationId,
        JsonNode payload) {

    public static final int SCHEMA_VERSION = 1;

    public static final String HEADER_EVENT_ID = "bv-event-id";
    public static final String HEADER_EVENT_TYPE = "bv-event-type";
    public static final String HEADER_CORRELATION_ID = "bv-correlation-id";
    public static final String HEADER_REPLAY_ATTEMPT = "bv-replay-attempt";
    public static final String HEADER_REPLAYED_BY = "bv-replayed-by";

    /** Parses and validates a message value. Malformed input is not retryable. */
    public static EventEnvelope parse(ObjectMapper json, String value) {
        if (value == null) {
            throw new InvalidEventException("null message value");
        }
        EventEnvelope e;
        try {
            e = json.readValue(value, EventEnvelope.class);
        } catch (Exception ex) {
            throw new InvalidEventException("unparseable envelope: " + ex.getClass().getSimpleName());
        }
        if (e.eventId() == null || e.eventType() == null || e.aggregateId() == null || e.occurredAt() == null
                || e.payload() == null) {
            throw new InvalidEventException("envelope missing required fields");
        }
        if (e.schemaVersion() != SCHEMA_VERSION) {
            throw new InvalidEventException("unsupported schemaVersion " + e.schemaVersion());
        }
        return e;
    }
}
