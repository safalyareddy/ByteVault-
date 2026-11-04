package dev.bytevault.controlplane.security;

import dev.bytevault.controlplane.audit.AuditWriter;
import dev.bytevault.controlplane.web.ApiException;
import dev.bytevault.protocol.Sha256;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Accounts and opaque bearer tokens. A token is 32 random bytes shown to the client once; only
 * its SHA-256 is stored, with an expiry, and logout revokes it. Login does the same BCrypt work
 * whether or not the user exists, so response time does not reveal valid usernames.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");

    private final JdbcClient jdbc;
    private final AuditWriter audit;
    private final SecurityProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    /** Hash of a random password, used to equalise login timing for unknown usernames. */
    private final String dummyHash;

    public UserService(JdbcClient jdbc, AuditWriter audit, SecurityProperties props) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    private record UserRow(UUID id, String username, String passwordHash, String role, boolean disabled) {}

    public record IssuedToken(String token, OffsetDateTime expiresAt, AuthenticatedUser user) {}

    /** Creates the bootstrap ADMIN once, when no user exists yet. */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapAdmin() {
        long users = jdbc.sql("SELECT count(*) FROM users").query(Long.class).single();
        if (users > 0) {
            return;
        }
        String password = props.bootstrapAdminPassword();
        if (password == null || password.isBlank()) {
            log.warn("No users exist and BV_BOOTSTRAP_ADMIN_PASSWORD is not set: nobody can log in yet");
            return;
        }
        String username = props.bootstrapAdminUsername();
        create(username, password, Role.ADMIN, AuditWriter.SYSTEM);
        log.info("Bootstrap ADMIN account '{}' created", username);
    }

    public AuthenticatedUser create(String username, String password, Role role, String actor) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USERNAME",
                    "username must be 3-64 chars of lowercase letters, digits, '.', '_' or '-'");
        }
        validatePassword(password);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO users (id, username, password_hash, role) VALUES (:id, :u, :h, :r)")
                    .param("id", id).param("u", username).param("h", encoder.encode(password)).param("r", role.name())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "username already exists");
        }
        audit.record(actor, "USER_CREATED", "USER", username, Map.of("role", role.name()));
        return new AuthenticatedUser(id, username, role);
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < SecurityProperties.MIN_PASSWORD_LENGTH || password.length() > 72) {
            // 72 bytes is BCrypt's input limit; longer passwords would be silently truncated.
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD",
                    "password must be " + SecurityProperties.MIN_PASSWORD_LENGTH + "-72 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "password must be at most 72 bytes");
        }
    }

    public Optional<IssuedToken> login(String username, String password) {
        Optional<UserRow> user = username == null ? Optional.empty() : jdbc.sql(
                        "SELECT id, username, password_hash, role, disabled FROM users WHERE username = :u")
                .param("u", username).query(UserRow.class).optional();
        boolean ok = encoder.matches(password == null ? "" : password,
                user.map(UserRow::passwordHash).orElse(dummyHash));
        if (!ok || user.isEmpty() || user.get().disabled()) {
            audit.record(username == null ? "anonymous" : truncate(username), "LOGIN_FAILED", "USER",
                    username == null ? "-" : truncate(username), Map.of());
            return Optional.empty();
        }
        UserRow u = user.get();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime expires = OffsetDateTime.now().plus(props.tokenTtl());
        jdbc.sql("INSERT INTO api_tokens (token_hash, user_id, expires_at) VALUES (:h, :u, :e)")
                .param("h", hash(token)).param("u", u.id()).param("e", expires).update();
        audit.record(u.username(), "LOGIN", "USER", u.username(), Map.of("expiresAt", expires.toString()));
        return Optional.of(new IssuedToken(token, expires, new AuthenticatedUser(u.id(), u.username(),
                Role.valueOf(u.role()))));
    }

    private record TokenRow(UUID id, String username, String role) {}

    /** Resolves a presented bearer token to its user if valid, unexpired, unrevoked and enabled. */
    public Optional<AuthenticatedUser> authenticate(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT u.id, u.username, u.role FROM api_tokens t JOIN users u ON u.id = t.user_id
                 WHERE t.token_hash = :h AND t.revoked_at IS NULL AND t.expires_at > now() AND NOT u.disabled
                """).param("h", hash(token)).query(TokenRow.class).optional()
                .map(r -> new AuthenticatedUser(r.id(), r.username(), Role.valueOf(r.role())));
    }

    public void logout(String token, String username) {
        jdbc.sql("UPDATE api_tokens SET revoked_at = now() WHERE token_hash = :h AND revoked_at IS NULL")
                .param("h", hash(token)).update();
        audit.record(username, "LOGOUT", "USER", username, Map.of());
    }

    static String hash(String token) {
        return Sha256.of(token.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
