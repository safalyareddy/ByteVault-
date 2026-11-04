package dev.bytevault.controlplane.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Operational configuration for the control plane. Bound from {@code bytevault.*}; every value has
 * an environment-variable override documented in {@code .env.example}. Invalid combinations fail
 * startup rather than running with unsafe settings.
 */
@Validated
@ConfigurationProperties(prefix = "bytevault")
public record BytevaultProperties(
        @Min(65_536) @Max(67_108_864) int chunkSizeBytes,
        @Min(1) @Max(5) int replicationFactor,
        @Min(0) long maxUploadBytes,
        @NotNull Duration heartbeatInterval,
        @NotNull Duration suspectAfter,
        @NotNull Duration offlineAfter,
        @NotNull Duration scrubInterval,
        @Valid @NotNull Kafka kafka) {

    public record Kafka(@Min(1) int topicPartitions, @Min(1) short topicReplicationFactor) {}

    @AssertTrue(message = "require heartbeatInterval < suspectAfter < offlineAfter")
    public boolean isHeartbeatThresholdOrderValid() {
        if (heartbeatInterval == null || suspectAfter == null || offlineAfter == null) {
            return true; // reported by @NotNull
        }
        return heartbeatInterval.compareTo(suspectAfter) < 0 && suspectAfter.compareTo(offlineAfter) < 0;
    }
}
