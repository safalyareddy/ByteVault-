package dev.bytevault.controlplane.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Builds the event timeline ({@code event_log}) shown by the operations console. */
@Component
public class TimelineConsumer {

    public static final String GROUP = "bv-timeline";

    private final IdempotentProcessor processor;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public TimelineConsumer(IdempotentProcessor processor, JdbcClient jdbc, ObjectMapper json) {
        this.processor = processor;
        this.jdbc = jdbc;
        this.json = json;
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = {Topics.OBJECT_EVENTS, Topics.CHUNK_EVENTS,
            Topics.NODE_EVENTS, Topics.REPLICATION_EVENTS, Topics.REPAIR_RESULTS})
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.parse(json, record.value());
        processor.process(GROUP, event, e -> jdbc.sql("""
                INSERT INTO event_log (event_id, event_type, aggregate_type, aggregate_id, aggregate_version,
                                       correlation_id, occurred_at, topic, kafka_partition, kafka_offset, payload)
                VALUES (:id, :type, :at, :aid, :av, :cid, :occurred, :topic, :p, :o, CAST(:payload AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("id", e.eventId()).param("type", e.eventType()).param("at", e.aggregateType())
                .param("aid", e.aggregateId()).param("av", e.aggregateVersion()).param("cid", e.correlationId())
                .param("occurred", java.sql.Timestamp.from(e.occurredAt())).param("topic", record.topic())
                .param("p", record.partition()).param("o", record.offset()).param("payload", e.payload().toString())
                .update());
    }
}
