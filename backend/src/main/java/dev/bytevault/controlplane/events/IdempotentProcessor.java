package dev.bytevault.controlplane.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a consumer's effect exactly once per (consumer group, event ID) as far as PostgreSQL is
 * concerned: the dedupe row and the effect commit in one transaction (docs/kafka-events.md rule 1).
 * Redelivery after a crash between commit and offset acknowledgement is therefore a no-op.
 */
@Component
public class IdempotentProcessor {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventProcessingHook hook;
    private final MeterRegistry meters;

    public IdempotentProcessor(JdbcClient jdbc, TransactionTemplate tx,
                               org.springframework.beans.factory.ObjectProvider<EventProcessingHook> hook,
                               MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.hook = hook.getIfAvailable(() -> EventProcessingHook.NO_OP);
        this.meters = meters;
    }

    /** Returns true if the effect ran, false if this event was already processed by the group. */
    public boolean process(String consumerGroup, EventEnvelope event, Consumer<EventEnvelope> effect) {
        boolean applied = Boolean.TRUE.equals(tx.execute(status -> {
            int inserted = jdbc.sql("INSERT INTO processed_events (consumer_group, event_id) VALUES (:g, :e) "
                    + "ON CONFLICT DO NOTHING").param("g", consumerGroup).param("e", event.eventId()).update();
            if (inserted == 0) {
                return false;
            }
            hook.beforeApply(consumerGroup, event);
            effect.accept(event);
            return true;
        }));
        meters.counter("bv_events_consumed_total", "group", consumerGroup,
                "result", applied ? "applied" : "duplicate").increment();
        hook.afterCommit(consumerGroup, event);
        return applied;
    }

    /**
     * For per-aggregate ordering: records {@code version} as applied unless an equal or newer
     * version already was. Returns false for stale events, which the caller then skips.
     */
    public boolean advanceVersion(String consumerGroup, String aggregateId, long version) {
        return jdbc.sql("""
                INSERT INTO projection_versions (consumer_group, aggregate_id, version) VALUES (:g, :a, :v)
                ON CONFLICT (consumer_group, aggregate_id) DO UPDATE SET version = EXCLUDED.version
                 WHERE projection_versions.version < EXCLUDED.version
                """).param("g", consumerGroup).param("a", aggregateId).param("v", version).update() == 1;
    }
}
