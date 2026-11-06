package dev.bytevault.controlplane.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytevault.controlplane.events.EventEnvelope;
import dev.bytevault.controlplane.events.JitteredBackOff;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes {@code outbox_events} to Kafka (ADR-0006). Each cycle claims the oldest unpublished
 * rows with {@code FOR UPDATE SKIP LOCKED}, sends them in order with the aggregate key, waits for
 * the broker acknowledgement, and marks exactly the acknowledged prefix as published.
 *
 * <p>On the first failure the cycle stops, so later events never overtake an earlier one for the
 * same key, and the publisher backs off globally (exponential with jitter): a broker outage
 * affects every row alike. A crash after publish but before marking yields a duplicate, which
 * consumers deduplicate by event ID.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 100;
    private static final long ACK_TIMEOUT_SECONDS = 15;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final JitteredBackOff backOff = new JitteredBackOff(500, 2.0, 30_000, Integer.MAX_VALUE, 0.2);
    private final Counter published;
    private final Counter failures;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private volatile int consecutiveFailures;
    private volatile long pausedUntilMillis;

    public OutboxPublisher(JdbcClient jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                           ObjectMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.json = json;
        this.published = Counter.builder("bv_outbox_published_total").register(meters);
        this.failures = Counter.builder("bv_outbox_publish_failures_total").register(meters);
        Gauge.builder("bv_outbox_backlog", backlog, AtomicLong::get)
                .description("Unpublished outbox rows at the last publisher cycle").register(meters);
        Gauge.builder("bv_outbox_oldest_unpublished_age_seconds", oldestAgeSeconds, AtomicLong::get).register(meters);
    }

    private record OutboxRow(UUID id, String aggregateType, String aggregateId, long aggregateVersion,
                             String eventType, String topic, String messageKey, String payload, String correlationId,
                             OffsetDateTime createdAt) {}

    @Scheduled(initialDelay = 1_000, fixedDelayString = "${bytevault.outbox.poll-interval:500ms}")
    public void publishPending() {
        refreshGauges();
        if (System.currentTimeMillis() < pausedUntilMillis) {
            return;
        }
        int sent;
        do {
            sent = publishBatch();
        } while (sent == BATCH && System.currentTimeMillis() >= pausedUntilMillis);
        refreshGauges();
    }

    /** Publishes one batch; returns how many rows were marked published. */
    int publishBatch() {
        Integer result = tx.execute(status -> {
            List<OutboxRow> rows = jdbc.sql("""
                    SELECT id, aggregate_type, aggregate_id, aggregate_version, event_type, topic, message_key,
                           payload::text AS payload, correlation_id, created_at
                      FROM outbox_events
                     WHERE published_at IS NULL
                     ORDER BY created_at, id
                     LIMIT :lim
                       FOR UPDATE SKIP LOCKED
                    """).param("lim", BATCH).query(OutboxRow.class).list();
            if (rows.isEmpty()) {
                return 0;
            }
            List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(rows.size());
            for (OutboxRow row : rows) {
                futures.add(kafka.send(toRecord(row)));
            }
            int acked = 0;
            for (int i = 0; i < rows.size(); i++) {
                OutboxRow row = rows.get(i);
                try {
                    futures.get(i).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    String error = rootMessage(e);
                    jdbc.sql("UPDATE outbox_events SET publish_attempts = publish_attempts + 1, last_error = :err "
                            + "WHERE id = :id").param("err", error).param("id", row.id()).update();
                    onFailure(error, rows.size() - acked);
                    break;
                }
                jdbc.sql("UPDATE outbox_events SET published_at = now(), publish_attempts = publish_attempts + 1, "
                        + "last_error = NULL WHERE id = :id").param("id", row.id()).update();
                acked++;
            }
            return acked;
        });
        int acked = result == null ? 0 : result;
        if (acked > 0) {
            published.increment(acked);
            if (consecutiveFailures > 0) {
                log.info("Outbox publishing resumed after {} failed cycle(s)", consecutiveFailures);
            }
            consecutiveFailures = 0;
        }
        return acked;
    }

    private ProducerRecord<String, String> toRecord(OutboxRow row) {
        try {
            EventEnvelope envelope = new EventEnvelope(row.id(), row.eventType(), EventEnvelope.SCHEMA_VERSION,
                    row.aggregateType(), row.aggregateId(), row.aggregateVersion(), row.createdAt().toInstant(),
                    row.correlationId(), json.readTree(row.payload()));
            ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.messageKey(),
                    json.writeValueAsString(envelope));
            record.headers().add(EventEnvelope.HEADER_EVENT_ID, row.id().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(EventEnvelope.HEADER_EVENT_TYPE, row.eventType().getBytes(StandardCharsets.UTF_8));
            if (row.correlationId() != null) {
                record.headers().add(EventEnvelope.HEADER_CORRELATION_ID,
                        row.correlationId().getBytes(StandardCharsets.UTF_8));
            }
            return record;
        } catch (Exception e) {
            throw new IllegalStateException("cannot build envelope for outbox row " + row.id(), e);
        }
    }

    private void onFailure(String error, int remaining) {
        failures.increment();
        int n = ++consecutiveFailures;
        long delay = backOff.jittered(backOff.baseDelay(Math.min(n - 1, 20)));
        pausedUntilMillis = System.currentTimeMillis() + delay;
        if (n == 1 || n % 10 == 0) {
            log.warn("Outbox publish failed ({} consecutive, {} row(s) waiting in batch), retrying in {} ms: {}",
                    n, remaining, delay, error);
        }
    }

    private void refreshGauges() {
        try {
            backlog.set(jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                    .query(Long.class).single());
            oldestAgeSeconds.set(jdbc.sql("SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::bigint "
                    + "FROM outbox_events WHERE published_at IS NULL").query(Long.class).single());
        } catch (RuntimeException e) {
            log.debug("Could not refresh outbox gauges: {}", e.toString());
        }
    }

    public long backlog() {
        return backlog.get();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
