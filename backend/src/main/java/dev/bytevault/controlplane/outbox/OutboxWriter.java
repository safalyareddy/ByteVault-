package dev.bytevault.controlplane.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytevault.controlplane.web.CorrelationIdFilter;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Appends domain events to {@code outbox_events} inside the caller's transaction (ADR-0006). It
 * refuses to run outside a transaction, because an event written separately from its state change
 * is exactly the dual-write the outbox exists to prevent. Publishing to Kafka is Phase 4.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID append(String aggregateType, String aggregateId, long aggregateVersion, String eventType,
                       String topic, String messageKey, Map<String, ?> payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox events must be written inside the state-change transaction");
        }
        UUID eventId = UUID.randomUUID();
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserialisable event payload for " + eventType, e);
        }
        jdbc.sql("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, aggregate_version, event_type, topic,
                                           message_key, payload, correlation_id)
                VALUES (:id, :at, :aid, :av, :et, :topic, :key, CAST(:payload AS jsonb), :cid)
                """)
                .param("id", eventId).param("at", aggregateType).param("aid", aggregateId)
                .param("av", aggregateVersion).param("et", eventType).param("topic", topic)
                .param("key", messageKey).param("payload", body).param("cid", CorrelationIdFilter.current())
                .update();
        return eventId;
    }
}
