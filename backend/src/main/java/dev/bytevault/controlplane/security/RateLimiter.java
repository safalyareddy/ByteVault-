package dev.bytevault.controlplane.security;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets keyed by caller. Each bucket holds up to {@code perMinute} tokens and
 * refills continuously. Single-instance by design (one control plane, ADR-0001); idle buckets
 * are evicted so memory stays bounded.
 */
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Result of an attempt: allowed, or how many seconds until a token is available. */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    public Decision tryAcquire(String key, int perMinute) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(perMinute, clock.millis()));
        return b.take(perMinute, clock.millis());
    }

    @Scheduled(fixedDelay = 60_000)
    void evictIdle() {
        long cutoff = clock.millis() - 10 * 60_000;
        buckets.entrySet().removeIf(e -> e.getValue().lastSeen < cutoff);
    }

    int size() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;
        private volatile long lastSeen;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastSeen = now;
        }

        synchronized Decision take(int perMinute, long now) {
            double ratePerMs = perMinute / 60_000.0;
            tokens = Math.min(perMinute, tokens + (now - lastRefill) * ratePerMs);
            lastRefill = now;
            lastSeen = now;
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, 0);
            }
            long waitMs = (long) Math.ceil((1 - tokens) / ratePerMs);
            return new Decision(false, Math.max(1, (waitMs + 999) / 1000));
        }
    }
}
