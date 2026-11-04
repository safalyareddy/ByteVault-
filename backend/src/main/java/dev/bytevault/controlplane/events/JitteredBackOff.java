package dev.bytevault.controlplane.events;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Bounded exponential backoff with ±{@code jitter} randomisation (docs/kafka-events.md):
 * delay(n) = min(initial × multiplier^n, max) × (1 ± jitter), for at most {@code maxRetries}
 * retries, after which {@link BackOffExecution#STOP} is returned.
 */
public final class JitteredBackOff implements BackOff {

    private final long initialMillis;
    private final double multiplier;
    private final long maxMillis;
    private final int maxRetries;
    private final double jitter;

    public JitteredBackOff(long initialMillis, double multiplier, long maxMillis, int maxRetries, double jitter) {
        if (initialMillis <= 0 || multiplier < 1 || maxMillis < initialMillis || maxRetries < 0 || jitter < 0
                || jitter >= 1) {
            throw new IllegalArgumentException("invalid backoff parameters");
        }
        this.initialMillis = initialMillis;
        this.multiplier = multiplier;
        this.maxMillis = maxMillis;
        this.maxRetries = maxRetries;
        this.jitter = jitter;
    }

    public int maxRetries() {
        return maxRetries;
    }

    /** The un-jittered delay before retry {@code n} (0-based). */
    public long baseDelay(int n) {
        double d = initialMillis * Math.pow(multiplier, n);
        return (long) Math.min(d, maxMillis);
    }

    public long jittered(long base) {
        double factor = 1 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        return Math.max(1, Math.round(base * factor));
    }

    @Override
    public BackOffExecution start() {
        return new BackOffExecution() {
            private int attempt;

            @Override
            public long nextBackOff() {
                if (attempt >= maxRetries) {
                    return STOP;
                }
                return jittered(baseDelay(attempt++));
            }
        };
    }
}
