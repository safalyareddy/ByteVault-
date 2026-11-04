package dev.bytevault.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytevault.controlplane.events.EventEnvelope;
import dev.bytevault.controlplane.events.EventProcessingHook;
import dev.bytevault.controlplane.events.ObjectStatsConsumer;
import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Phase 4 gate: outbox publishing, consumer idempotency, retries, DLQ and guarded replay against
 * a real Kafka broker. Events enter through the production path (OutboxWriter in a transaction,
 * then OutboxPublisher) unless a test deliberately bypasses it to inject a duplicate or poison.
 *
 * <p>All assertions are deltas against the state at the start of each test, so tests stay
 * independent while sharing one broker and database.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.password=test",
        "bytevault.outbox.poll-interval=200ms"})
@Import({ContainersConfig.class, KafkaReliabilityIT.HookConfig.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaReliabilityIT {


    /** Injects failures into the object-stats consumer for one chosen event at a time. */
    static class FaultHook implements EventProcessingHook {
        volatile UUID target;
        final AtomicInteger failBeforeApply = new AtomicInteger();
        final AtomicInteger crashAfterCommit = new AtomicInteger();
        final AtomicInteger beforeApplyCalls = new AtomicInteger();

        void reset() {
            target = null;
            failBeforeApply.set(0);
            crashAfterCommit.set(0);
            beforeApplyCalls.set(0);
        }

        @Override
        public void beforeApply(String group, EventEnvelope event) {
            if (group.equals(ObjectStatsConsumer.GROUP) && event.eventId().equals(target)) {
                beforeApplyCalls.incrementAndGet();
                if (failBeforeApply.getAndDecrement() > 0) {
                    throw new IllegalStateException("injected transient failure");
                }
            }
        }

        @Override
        public void afterCommit(String group, EventEnvelope event) {
            if (group.equals(ObjectStatsConsumer.GROUP) && event.eventId().equals(target)
                    && crashAfterCommit.getAndDecrement() > 0) {
                throw new IllegalStateException("injected crash after commit, before offset ack");
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HookConfig {
        @Bean
        FaultHook faultHook() {
            return new FaultHook();
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired OutboxWriter outbox;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired FaultHook hook;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired KafkaContainer kafkaContainer;
    @Autowired TestRestTemplate http;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void resetHook() {
        hook.reset();
        TestAuth.asAdmin(http);
    }

    // ------------------------------------------------------------------ helpers

    record Emitted(UUID eventId, UUID objectId, long size) {}

    /** Writes an ObjectCompleted event through the real outbox path. */
    Emitted emitCompleted(long size) {
        UUID objectId = UUID.randomUUID();
        UUID eventId = tx.execute(s -> outbox.append("OBJECT", objectId.toString(), 1, "ObjectCompleted",
                Topics.OBJECT_EVENTS, objectId.toString(), Map.of("objectId", objectId, "totalSize", size)));
        return new Emitted(eventId, objectId, size);
    }

    long storedBytes() {
        return jdbc.queryForObject("SELECT stored_bytes FROM object_stats WHERE id = 1", Long.class);
    }

    int timelineRows(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_log WHERE event_id = ?", Integer.class, eventId);
    }

    int processed(String group, UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
                Integer.class, group, eventId);
    }

    long unpublished() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
    }

    double counter(String name, String... tags) {
        var c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    static void await(String what, Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    void awaitApplied(Emitted e) {
        await("stats applied " + e.eventId(), Duration.ofSeconds(60),
                () -> processed(ObjectStatsConsumer.GROUP, e.eventId()) == 1);
        await("timeline row " + e.eventId(), Duration.ofSeconds(60), () -> timelineRows(e.eventId()) == 1);
    }

    String envelopeJson(Emitted e) throws Exception {
        return json.writeValueAsString(new EventEnvelope(e.eventId(), "ObjectCompleted", 1, "OBJECT",
                e.objectId().toString(), 1, Instant.now(), "it-correlation-0001",
                json.valueToTree(Map.of("objectId", e.objectId(), "totalSize", e.size()))));
    }

    /** {@code token == null}: use the logged-in admin; otherwise send that (invalid) bearer token. */
    HttpHeaders admin(String token) {
        HttpHeaders h = token == null ? new HttpHeaders() : TestAuth.bearer(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void outboxEventIsPublishedAndEachConsumerAppliesItOnce() {
        long before = storedBytes();
        Emitted e = emitCompleted(1234);
        awaitApplied(e);

        assertThat(storedBytes() - before).isEqualTo(1234);
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?",
                Boolean.class, e.eventId())).isTrue();
        Map<String, Object> row = jdbc.queryForMap("SELECT topic, aggregate_id, event_type FROM event_log WHERE event_id = ?",
                e.eventId());
        assertThat(row).containsEntry("topic", Topics.OBJECT_EVENTS).containsEntry("event_type", "ObjectCompleted")
                .containsEntry("aggregate_id", e.objectId().toString());
    }

    @Test
    @Order(2)
    void duplicateDeliveryProducesOneLogicalEffect() throws Exception {
        long before = storedBytes();
        double duplicatesBefore = counter("bv_events_consumed_total", "group", ObjectStatsConsumer.GROUP,
                "result", "duplicate");
        Emitted e = new Emitted(UUID.randomUUID(), UUID.randomUUID(), 777);
        String value = envelopeJson(e);
        // The same message delivered twice, as after a publisher crash between send and mark.
        kafka.send(Topics.OBJECT_EVENTS, e.objectId().toString(), value).get(10, TimeUnit.SECONDS);
        kafka.send(Topics.OBJECT_EVENTS, e.objectId().toString(), value).get(10, TimeUnit.SECONDS);

        awaitApplied(e);
        await("second delivery seen as duplicate", Duration.ofSeconds(30), () -> counter("bv_events_consumed_total",
                "group", ObjectStatsConsumer.GROUP, "result", "duplicate") >= duplicatesBefore + 1);
        assertThat(storedBytes() - before).isEqualTo(777);
        assertThat(timelineRows(e.eventId())).isEqualTo(1);
    }

    @Test
    @Order(3)
    void crashAfterCommitBeforeAckIsRedeliveredAndSkipped() {
        long before = storedBytes();
        hook.crashAfterCommit.set(1);
        UUID objectId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        hook.target = eventId;
        Emitted target = new Emitted(eventId, objectId, 4096);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, aggregate_version, event_type, topic,
                                           message_key, payload)
                VALUES (?, 'OBJECT', ?, 1, 'ObjectCompleted', ?, ?, CAST(? AS jsonb))
                """, eventId, objectId.toString(), Topics.OBJECT_EVENTS, objectId.toString(),
                "{\"objectId\":\"" + objectId + "\",\"totalSize\":4096}"));
        awaitApplied(target);
        await("redelivery after injected crash", Duration.ofSeconds(30), () -> hook.crashAfterCommit.get() < 0);

        assertThat(storedBytes() - before).as("applied once despite redelivery").isEqualTo(4096);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlq_events WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    @Test
    @Order(4)
    void transientFailuresAreRetriedThenSucceedOnce() {
        long before = storedBytes();
        UUID eventId = UUID.randomUUID();
        UUID objectId = UUID.randomUUID();
        hook.target = eventId;
        hook.failBeforeApply.set(2);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, aggregate_version, event_type, topic,
                                           message_key, payload)
                VALUES (?, 'OBJECT', ?, 1, 'ObjectCompleted', ?, ?, CAST(? AS jsonb))
                """, eventId, objectId.toString(), Topics.OBJECT_EVENTS, objectId.toString(),
                "{\"objectId\":\"" + objectId + "\",\"totalSize\":2048}"));
        awaitApplied(new Emitted(eventId, objectId, 2048));

        assertThat(hook.beforeApplyCalls.get()).as("2 injected failures + 1 success").isEqualTo(3);
        assertThat(storedBytes() - before).isEqualTo(2048);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlq_events WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    @Test
    @Order(5)
    void exhaustedRetriesGoToDlqAndGuardedReplayAppliesOnce() throws Exception {
        long before = storedBytes();
        UUID eventId = UUID.randomUUID();
        UUID objectId = UUID.randomUUID();
        hook.target = eventId;
        hook.failBeforeApply.set(1_000); // fails every attempt
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, aggregate_version, event_type, topic,
                                           message_key, payload, correlation_id)
                VALUES (?, 'OBJECT', ?, 1, 'ObjectCompleted', ?, ?, CAST(? AS jsonb), 'dlq-correlation-01')
                """, eventId, objectId.toString(), Topics.OBJECT_EVENTS, objectId.toString(),
                "{\"objectId\":\"" + objectId + "\",\"totalSize\":5000}"));

        await("DLQ entry recorded", Duration.ofSeconds(90), () -> jdbc.queryForObject(
                "SELECT count(*) FROM dlq_events WHERE event_id = ?", Integer.class, eventId) == 1);
        Map<String, Object> dlq = jdbc.queryForMap("SELECT id, original_topic, original_key, consumer_group, attempts, "
                + "correlation_id, failure_reason, status FROM dlq_events WHERE event_id = ?", eventId);
        assertThat(dlq).containsEntry("original_topic", Topics.OBJECT_EVENTS)
                .containsEntry("original_key", objectId.toString())
                .containsEntry("consumer_group", ObjectStatsConsumer.GROUP)
                .containsEntry("attempts", 5)
                .containsEntry("correlation_id", "dlq-correlation-01")
                .containsEntry("status", "PENDING");
        assertThat((String) dlq.get("failure_reason")).contains("injected transient failure");
        assertThat(hook.beforeApplyCalls.get()).as("1 attempt + 4 retries").isEqualTo(5);
        assertThat(storedBytes()).isEqualTo(before);
        // The timeline group was unaffected and applied it normally.
        assertThat(timelineRows(eventId)).isEqualTo(1);

        String dlqId = dlq.get("id").toString();
        String path = "/api/v1/admin/dlq/" + dlqId + "/replay";
        HttpEntity<Map<String, String>> noToken = new HttpEntity<>(Map.of("reason", "x"), admin("not-a-real-token-0000000000"));
        assertThat(http.exchange(path, HttpMethod.POST, noToken, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        HttpEntity<Map<String, String>> badToken = new HttpEntity<>(Map.of("reason", "x"), admin("wrong-token-1111111111111111"));
        assertThat(http.exchange(path, HttpMethod.POST, badToken, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        HttpEntity<Map<String, String>> noReason = new HttpEntity<>(Map.of(), admin(null));
        assertThat(http.exchange(path, HttpMethod.POST, noReason, JsonNode.class).getStatusCode().value()).isEqualTo(400);

        hook.failBeforeApply.set(0); // "the fix is deployed"
        HttpEntity<Map<String, String>> ok = new HttpEntity<>(Map.of("reason", "bug fixed in stats consumer"),
                admin(null));
        var res = http.exchange(path, HttpMethod.POST, ok, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("replayAttempt").asInt()).isEqualTo(1);

        await("replayed event applied", Duration.ofSeconds(30),
                () -> processed(ObjectStatsConsumer.GROUP, eventId) == 1);
        assertThat(storedBytes() - before).isEqualTo(5000);
        assertThat(timelineRows(eventId)).as("timeline deduplicated the replay").isEqualTo(1);
        assertThat(http.exchange(path, HttpMethod.POST, ok, JsonNode.class).getStatusCode().value())
                .as("cannot replay the same entry twice").isEqualTo(409);
        Map<String, Object> audit = jdbc.queryForMap("SELECT actor, details->>'reason' AS reason, "
                + "details->>'replayAttempt' AS attempt FROM audit_events WHERE action = 'DLQ_REPLAY' AND resource_id = ?",
                dlqId);
        assertThat(audit).containsEntry("actor", "admin").containsEntry("reason", "bug fixed in stats consumer")
                .containsEntry("attempt", "1");
    }

    @Test
    @Order(6)
    void malformedMessagesSkipRetriesAndAreDeadLettered() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        kafka.send(Topics.OBJECT_EVENTS, key, "{not json").get(10, TimeUnit.SECONDS);
        await("poison recorded for both groups", Duration.ofSeconds(30), () -> jdbc.queryForObject(
                "SELECT count(*) FROM dlq_events WHERE original_key = ?", Integer.class, key) == 2);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT consumer_group, attempts FROM dlq_events "
                + "WHERE original_key = ? ORDER BY consumer_group", key);
        assertThat(rows).extracting(r -> r.get("consumer_group"))
                .containsExactly(ObjectStatsConsumer.GROUP, "bv-timeline");
        assertThat(rows).extracting(r -> r.get("attempts")).containsOnly(1);
    }

    @Test
    @Order(7)
    void stoppedConsumerCatchesUpWithoutLossOrDuplication() {
        long before = storedBytes();
        var container = listeners.getListenerContainer(ObjectStatsConsumer.GROUP);
        container.stop();
        try {
            List<Emitted> events = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                events.add(emitCompleted(100L * i));
            }
            await("published while consumer down", Duration.ofSeconds(30), () -> events.stream().allMatch(
                    e -> timelineRows(e.eventId()) == 1));
            assertThat(storedBytes()).as("stats consumer is stopped").isEqualTo(before);
            container.start();
            events.forEach(this::awaitApplied);
            assertThat(storedBytes() - before).isEqualTo(100 + 200 + 300 + 400 + 500);
        } finally {
            if (!container.isRunning()) {
                container.start();
            }
        }
    }

    @Test
    @Order(8)
    void kafkaOutageAccumulatesOutboxThenDrainsOnRecovery() {
        long before = storedBytes();
        long backlogBefore = unpublished();
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafkaContainer.getContainerId()).exec();
        List<Emitted> events = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                events.add(emitCompleted(1000));
            }
            await("outage noticed by publisher", Duration.ofSeconds(30),
                    () -> counter("bv_outbox_publish_failures_total") >= 1);
            assertThat(unpublished() - backlogBefore).as("events held in the outbox").isEqualTo(5);
            assertThat(events).allMatch(e -> timelineRows(e.eventId()) == 0);
            Integer attempts = jdbc.queryForObject("SELECT max(publish_attempts) FROM outbox_events WHERE id = ?",
                    Integer.class, events.get(0).eventId());
            assertThat(attempts).isGreaterThanOrEqualTo(1);
        } finally {
            docker.unpauseContainerCmd(kafkaContainer.getContainerId()).exec();
        }
        await("outbox drained", Duration.ofSeconds(120), () -> unpublished() == backlogBefore);
        events.forEach(this::awaitApplied);
        assertThat(storedBytes() - before).isEqualTo(5000);
    }
}
