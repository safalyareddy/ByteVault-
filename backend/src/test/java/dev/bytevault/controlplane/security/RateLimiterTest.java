package dev.bytevault.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** A clock the test can move. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-04T10:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void allowsBurstUpToLimitThenRefillsOverTime() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("k", 10).allowed()).isTrue();
        }
        RateLimiter.Decision denied = limiter.tryAcquire("k", 10);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 6L);

        clock.now = clock.now.plus(Duration.ofSeconds(6)); // 10/min = one token per 6 s
        assertThat(limiter.tryAcquire("k", 10).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 10).allowed()).isFalse();
    }

    @Test
    void keysAreIndependentAndIdleBucketsAreEvicted() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock);
        limiter.tryAcquire("a", 1);
        assertThat(limiter.tryAcquire("a", 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 1).allowed()).isTrue();

        clock.now = clock.now.plus(Duration.ofMinutes(11));
        limiter.evictIdle();
        assertThat(limiter.size()).isZero();
    }
}
