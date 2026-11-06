package dev.bytevault.controlplane.events;

/**
 * Extension point invoked around each consumer's effect. Production uses the no-op default; tests
 * substitute an implementation to inject failures at precise points (for example after the effect
 * commits but before the offset is acknowledged, i.e. a crash in that window).
 */
public interface EventProcessingHook {

    /** Called inside the effect transaction, before the effect. Throwing rolls the effect back. */
    default void beforeApply(String consumerGroup, EventEnvelope event) {}

    /** Called after the effect transaction committed and before the listener returns. */
    default void afterCommit(String consumerGroup, EventEnvelope event) {}

    EventProcessingHook NO_OP = new EventProcessingHook() {};
}
