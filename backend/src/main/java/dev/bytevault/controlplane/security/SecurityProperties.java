package dev.bytevault.controlplane.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Authentication and abuse limits. {@code bootstrapAdminPassword} creates the first ADMIN when
 * the users table is empty and is ignored afterwards; it must come from the environment.
 */
@Validated
@ConfigurationProperties(prefix = "bytevault.security")
public record SecurityProperties(
        String bootstrapAdminUsername,
        String bootstrapAdminPassword,
        @NotNull Duration tokenTtl,
        @Min(1) int loginAttemptsPerMinute,
        @Min(1) int apiRequestsPerMinute,
        @Min(1024) int maxJsonBodyBytes,
        @NotNull List<String> corsAllowedOrigins) {

    public static final int MIN_PASSWORD_LENGTH = 12;

    @Override
    public String toString() {
        return "SecurityProperties[bootstrapAdminUsername=" + bootstrapAdminUsername + ", tokenTtl=" + tokenTtl
                + ", loginAttemptsPerMinute=" + loginAttemptsPerMinute + ", apiRequestsPerMinute="
                + apiRequestsPerMinute + "]";
    }
}
