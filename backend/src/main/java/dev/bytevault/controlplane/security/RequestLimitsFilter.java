package dev.bytevault.controlplane.security;

import dev.bytevault.controlplane.web.ApiErrorWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Abuse limits applied after authentication: per-user API rate limit (per client IP when
 * anonymous), a per-IP login limit, and a size cap on non-chunk
 * request bodies. Chunk PUTs are bounded separately by their exact declared size and by
 * TransferPermits backpressure.
 */
public class RequestLimitsFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final SecurityProperties props;
    private final ApiErrorWriter errors;
    private final MeterRegistry meters;

    public RequestLimitsFilter(RateLimiter limiter, SecurityProperties props, ApiErrorWriter errors,
                               MeterRegistry meters) {
        this.limiter = limiter;
        this.props = props;
        this.errors = errors;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Node heartbeats are HMAC-authenticated and paced by nodes; actuator is localhost-only.
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        boolean chunkPut = "PUT".equals(req.getMethod()) && req.getRequestURI().matches("^/api/v1/uploads/[^/]+/chunks/\\d+$");
        long length = req.getContentLengthLong();
        if (!chunkPut && length > props.maxJsonBodyBytes()) {
            errors.write(req, res, 413, "REQUEST_TOO_LARGE", "request body exceeds " + props.maxJsonBodyBytes() + " bytes");
            return;
        }
        String ip = req.getRemoteAddr();
        if ("POST".equals(req.getMethod()) && req.getRequestURI().equals("/api/v1/auth/login")) {
            // Per IP across all usernames, with headroom; AuthController adds the stricter
            // per (IP, username) limit once the body has been parsed.
            if (reject(req, res, "login-ip:" + ip, props.loginAttemptsPerMinute() * 5, "login")) {
                return;
            }
        } else {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            String key = auth != null && auth.getPrincipal() instanceof AuthenticatedUser u ? "user:" + u.username() : "ip:" + ip;
            if (reject(req, res, key, props.apiRequestsPerMinute(), "api")) {
                return;
            }
        }
        chain.doFilter(chunkPut ? req : new LimitedBodyRequest(req, props.maxJsonBodyBytes()), res);
    }

    private boolean reject(HttpServletRequest req, HttpServletResponse res, String key, int perMinute, String scope)
            throws IOException {
        RateLimiter.Decision d = limiter.tryAcquire(key, perMinute);
        if (d.allowed()) {
            return false;
        }
        meters.counter("bv_rate_limited_total", "scope", scope).increment();
        res.setHeader("Retry-After", Long.toString(d.retryAfterSeconds()));
        errors.write(req, res, 429, "RATE_LIMITED", "too many requests; retry after " + d.retryAfterSeconds() + "s");
        return true;
    }
}
