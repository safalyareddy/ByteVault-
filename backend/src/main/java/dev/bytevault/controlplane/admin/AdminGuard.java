package dev.bytevault.controlplane.admin;

import dev.bytevault.controlplane.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Interim authorization for admin actions until Phase 6 roles exist: a shared admin token
 * ({@code BV_ADMIN_TOKEN}, at least 32 chars) compared in constant time. With no token configured
 * every admin action is refused. The audit actor is recorded as {@value #ACTOR} because the token
 * identifies a role, not a person.
 */
@Component
public class AdminGuard {

    public static final String HEADER = "X-BV-Admin-Token";
    public static final String ACTOR = "admin-token";

    private final byte[] token;

    public AdminGuard(@Value("${bytevault.admin-token:}") String token) {
        this.token = token == null || token.length() < 32 ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    public String requireAdmin(String presented) {
        if (token == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_DISABLED",
                    "admin actions are disabled: BV_ADMIN_TOKEN is not configured");
        }
        if (presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "admin authentication failed");
        }
        return ACTOR;
    }
}
