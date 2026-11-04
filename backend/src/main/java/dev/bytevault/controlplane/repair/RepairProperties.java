package dev.bytevault.controlplane.repair;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Self-healing settings (ADR-0007). Defaults are deliberately gentle on a laptop. */
@Validated
@ConfigurationProperties(prefix = "bytevault.repair")
public record RepairProperties(
        boolean enabled,
        @Min(1) @Max(16) int maxConcurrent,
        @NotNull Duration lease,
        @Min(1) @Max(20) int maxAttempts,
        @NotNull Duration capacityWait,
        @NotNull Scrub scrub) {

    public record Scrub(boolean enabled, @Min(1) @Max(10_000) int batchSize, @NotNull Duration pauseBetweenChunks) {}
}
