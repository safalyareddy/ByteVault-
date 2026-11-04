package dev.bytevault.node.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Identity and limits of one storage-node process. A node refuses to start without a valid ID,
 * a data directory and an auth secret of at least 32 characters.
 */
@Validated
@ConfigurationProperties(prefix = "bytevault.node")
public record NodeProperties(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,31}$") String id,
        @NotNull Path dataDir,
        String authSecret,
        @NotBlank String advertisedEndpoint,
        @NotBlank String controlPlaneUrl,
        @NotNull Duration heartbeatInterval,
        @Min(1) long maxChunkBytes) {

    public static final int PROTOCOL_VERSION = 1;
    public static final int MIN_SECRET_LENGTH = 32;

    /**
     * Checked as a derived property, not with {@code @Size} on the field: Spring's startup failure
     * report prints the rejected value of a failing field, which would leak the secret into logs.
     */
    @AssertTrue(message = "BV_NODE_AUTH_SECRET must be set and at least 32 characters")
    public boolean isAuthSecretStrongEnough() {
        return authSecret != null && authSecret.strip().length() >= MIN_SECRET_LENGTH;
    }

    @Override
    public String toString() {
        // Never print the secret.
        return "NodeProperties[id=" + id + ", dataDir=" + dataDir + ", advertisedEndpoint=" + advertisedEndpoint
                + ", controlPlaneUrl=" + controlPlaneUrl + ", heartbeatInterval=" + heartbeatInterval
                + ", maxChunkBytes=" + maxChunkBytes + "]";
    }
}
