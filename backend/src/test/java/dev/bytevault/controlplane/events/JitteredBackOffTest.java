package dev.bytevault.controlplane.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

class JitteredBackOffTest {

    @Test
    void baseDelaysGrowExponentiallyAndAreCapped() {
        JitteredBackOff b = new JitteredBackOff(500, 2.0, 30_000, 10, 0.2);
        assertThat(b.baseDelay(0)).isEqualTo(500);
        assertThat(b.baseDelay(1)).isEqualTo(1000);
        assertThat(b.baseDelay(4)).isEqualTo(8000);
        assertThat(b.baseDelay(6)).isEqualTo(30_000);
        assertThat(b.baseDelay(50)).isEqualTo(30_000);
    }

    @RepeatedTest(20)
    void jitterStaysWithinBoundsAndRetriesAreBounded() {
        JitteredBackOff b = new JitteredBackOff(500, 2.0, 30_000, 4, 0.2);
        BackOffExecution exec = b.start();
        long[] base = {500, 1000, 2000, 4000};
        for (long expected : base) {
            assertThat(exec.nextBackOff()).isBetween((long) (expected * 0.8), (long) (expected * 1.2));
        }
        assertThat(exec.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void rejectsNonsenseParameters() {
        assertThatThrownBy(() -> new JitteredBackOff(0, 2, 10, 1, 0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JitteredBackOff(10, 2, 5, 1, 0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JitteredBackOff(10, 2, 50, 1, 1.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
