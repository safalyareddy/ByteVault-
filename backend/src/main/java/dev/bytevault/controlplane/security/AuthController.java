package dev.bytevault.controlplane.security;

import dev.bytevault.controlplane.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final UserService users;
    private final CurrentUser current;
    private final RateLimiter limiter;
    private final SecurityProperties props;

    public AuthController(UserService users, CurrentUser current, RateLimiter limiter, SecurityProperties props) {
        this.users = users;
        this.current = current;
        this.limiter = limiter;
        this.props = props;
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 200) String password) {}

    public record LoginResponse(String token, String tokenType, OffsetDateTime expiresAt, String username, String role) {}

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        RateLimiter.Decision d = limiter.tryAcquire("login-user:" + http.getRemoteAddr() + ":" + request.username(),
                props.loginAttemptsPerMinute());
        if (!d.allowed()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "too many login attempts for this account; retry after " + d.retryAfterSeconds() + "s",
                    Map.of("retryAfterSeconds", d.retryAfterSeconds()));
        }
        return users.login(request.username(), request.password())
                .map(t -> ResponseEntity.ok().header("Cache-Control", "no-store").body(new LoginResponse(t.token(),
                        "Bearer", t.expiresAt(), t.user().username(), t.user().role().name())))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                        "invalid username or password"));
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        users.logout(header.substring(7).strip(), current.username());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/auth/me")
    public Map<String, String> me() {
        AuthenticatedUser u = current.require();
        return Map.of("username", u.username(), "role", u.role().name());
    }

    public record CreateUserRequest(@NotBlank String username, @NotBlank String password, @NotNull Role role) {}

    /** ADMIN only (path rule). */
    @PostMapping("/api/v1/admin/users")
    public ResponseEntity<Map<String, String>> createUser(@Valid @RequestBody CreateUserRequest request) {
        AuthenticatedUser u = users.create(request.username(), request.password(), request.role(), current.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("username", u.username(), "role", u.role().name()));
    }
}
