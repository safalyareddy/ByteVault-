package dev.bytevault.controlplane.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Records every DLQ message in {@code dlq_events} so operators can inspect and replay it. Must
 * never fail on a malformed value (it is the last stop), so it stores what it can parse.
 * Idempotent by DLQ position (partition, offset).
 */
@Component
public class DlqRecorder {

    public static final String GROUP = "bv-dlq-recorder";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DlqRecorder(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @KafkaListener(id = GROUP, groupId = GROUP, topics = Topics.STORAGE_DLQ)
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        UUID eventId = null;
        String eventType = null;
        String correlationId = null;
        try {
            JsonNode envelope = json.readTree(record.value() == null ? "null" : record.value());
            if (envelope.hasNonNull("eventId")) {
                eventId = UUID.fromString(envelope.get("eventId").asText());
            }
            eventType = envelope.path("eventType").asText(null);
            correlationId = envelope.path("correlationId").asText(null);
        } catch (Exception ignored) {
            // malformed value: still record it, with what the headers tell us
        }
        String originalTopic = text(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        Long originalTimestamp = number(record, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);
        String attempts = text(record, KafkaConsumerConfig.HEADER_ATTEMPTS);
        jdbc.sql("""
                INSERT INTO dlq_events (dlq_partition, dlq_offset, event_id, event_type, original_topic, original_key,
                                        original_partition, original_offset, consumer_group, failure_reason, attempts,
                                        failed_at, correlation_id, payload)
                VALUES (:dp, :do, :eid, :et, :ot, :ok, :op, :oo, :cg, :reason, :attempts, :failed, :cid, :payload)
                ON CONFLICT (dlq_partition, dlq_offset) DO NOTHING
                """)
                .param("dp", record.partition()).param("do", record.offset())
                .param("eid", eventId).param("et", eventType)
                .param("ot", originalTopic == null ? "unknown" : originalTopic)
                .param("ok", record.key())
                .param("op", number(record, KafkaHeaders.DLT_ORIGINAL_PARTITION) == null ? null
                        : number(record, KafkaHeaders.DLT_ORIGINAL_PARTITION).intValue())
                .param("oo", number(record, KafkaHeaders.DLT_ORIGINAL_OFFSET))
                .param("cg", text(record, KafkaConsumerConfig.HEADER_CONSUMER_GROUP))
                .param("reason", truncate(rootReason(text(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE)), 2000))
                .param("attempts", attempts == null ? null : Integer.valueOf(attempts))
                .param("failed", Timestamp.from(Instant.ofEpochMilli(record.timestamp())))
                .param("cid", correlationId)
                .param("payload", record.value() == null ? "" : record.value())
                .update();
    }

    private static String text(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /** Spring writes partition as 4-byte int and offset/timestamp as 8-byte long, big-endian. */
    private static Long number(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        if (h == null) {
            return null;
        }
        byte[] v = h.value();
        return switch (v.length) {
            case 4 -> (long) ByteBuffer.wrap(v).getInt();
            case 8 -> ByteBuffer.wrap(v).getLong();
            default -> null;
        };
    }

    /** Drops Spring's "Listener method '...' threw exception; " wrapper, keeping the actual cause. */
    static String rootReason(String message) {
        if (message == null) {
            return null;
        }
        int i = message.lastIndexOf("threw exception; ");
        return i >= 0 ? message.substring(i + "threw exception; ".length()) : message;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
