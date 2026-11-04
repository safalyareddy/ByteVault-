package dev.bytevault.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BytevaultPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static BytevaultProperties props(int chunk, int rf, Duration hb, Duration suspect, Duration offline) {
        return new BytevaultProperties(chunk, rf, 1L << 30, hb, suspect, offline, Duration.ofMinutes(10),
                new BytevaultProperties.Kafka(3, (short) 1));
    }

    @Test
    void defaultsAreValid() {
        var p = props(4 * 1024 * 1024, 3, Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45));
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    void rejectsOutOfRangeChunkSizeAndReplicationFactor() {
        var p = props(1024, 0, Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45));
        assertThat(validator.validate(p))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("chunkSizeBytes", "replicationFactor");
    }

    @Test
    void rejectsHeartbeatThresholdsOutOfOrder() {
        var p = props(4 * 1024 * 1024, 3, Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofSeconds(30));
        assertThat(validator.validate(p))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("heartbeatThresholdOrderValid");
    }
}
