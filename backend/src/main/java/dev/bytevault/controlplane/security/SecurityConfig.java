package dev.bytevault.controlplane.security;

import dev.bytevault.controlplane.web.ApiErrorWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Authorization rules (docs/security-threat-model.md). USER: own uploads and objects. ADMIN:
 * everything, including cluster inventory, repairs, events, stats, drain, scrub, DLQ replay and
 * user management. Stateless bearer tokens: no sessions, no cookies, so no CSRF surface.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, UserService users, RateLimiter limiter, SecurityProperties props,
                            ApiErrorWriter errors, MeterRegistry meters) throws Exception {
        BearerTokenFilter bearer = new BearerTokenFilter(users);
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(c -> c.configurationSource(cors(props)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a
                        // Streaming downloads finish on an ASYNC re-dispatch of an already-authorized
                        // request; re-checking it there fails after the response is committed.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // HMAC-authenticated by HeartbeatController (ADR-0010), not by user tokens.
                        .requestMatchers(HttpMethod.POST, "/internal/v1/nodes/*/heartbeat").permitAll()
                        // Bound to 127.0.0.1 / the Compose network only; see threat model T14.
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        // API description only (no data); lets clients and reviewers read the contract.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/api/v1/nodes/**", "/api/v1/repairs/**",
                                "/api/v1/events/**", "/api/v1/stats", "/api/v1/overview").hasRole(Role.ADMIN.name())
                        .requestMatchers("/api/v1/uploads/**", "/api/v1/objects/**", "/api/v1/auth/**")
                                .hasAnyRole(Role.USER.name(), Role.ADMIN.name())
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                errors.write(req, res, 401, "UNAUTHORIZED", "authentication required"))
                        .accessDeniedHandler((req, res, ex) ->
                                errors.write(req, res, 403, "FORBIDDEN", "insufficient role for this operation")))
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new RequestLimitsFilter(limiter, props, errors, meters), BearerTokenFilter.class);
        return http.build();
    }

    private static CorsConfigurationSource cors(SecurityProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.corsAllowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Chunk-SHA256",
                "X-Correlation-Id"));
        c.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
        c.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }

    /** Prevents Spring Boot from creating a default user with a generated password. */
    @Bean
    UserDetailsService noDefaultUser() {
        return new InMemoryUserDetailsManager();
    }
}
