package dev.bytevault.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 6 gate: authentication, role-based authorization, owner isolation, token lifecycle,
 * abuse limits and response hardening. Uses a raw TestRestTemplate (no auto-login) so every
 * request states exactly which credentials it carries.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainersConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityIT {

    static final String ALICE_PASSWORD = "alice-password-123";

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    String adminToken() {
        return TestAuth.login(http, TestAuth.ADMIN, TestAuth.ADMIN_PASSWORD);
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders h = token == null ? new HttpHeaders() : TestAuth.bearer(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    void ensureAlice(String admin) {
        call(HttpMethod.POST, "/api/v1/admin/users", admin,
                Map.of("username", "alice", "password", ALICE_PASSWORD, "role", "USER"));
    }

    @Test
    @Order(1)
    void publicEndpointsAreOpenAndEverythingElseNeedsAToken() {
        assertThat(call(HttpMethod.GET, "/api/v1/health", null, null).getStatusCode().value()).isEqualTo(200);
        for (String path : new String[] {"/api/v1/objects", "/api/v1/nodes", "/api/v1/repairs", "/api/v1/events",
                "/api/v1/stats", "/api/v1/auth/me", "/api/v1/does-not-exist"}) {
            ResponseEntity<JsonNode> res = call(HttpMethod.GET, path, null, null);
            assertThat(res.getStatusCode().value()).as(path).isEqualTo(401);
            assertThat(res.getBody().get("code").asText()).isEqualTo("UNAUTHORIZED");
            assertThat(res.getBody().get("correlationId").asText()).isNotBlank();
        }
        assertThat(call(HttpMethod.GET, "/api/v1/objects", "garbage-token-that-is-long-enough", null)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void loginIssuesATokenAndBadCredentialsAreRejectedAndAudited() {
        ResponseEntity<JsonNode> bad = call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("username", "admin", "password", "wrong-password-0000"));
        assertThat(bad.getStatusCode().value()).isEqualTo(401);
        assertThat(bad.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        ResponseEntity<JsonNode> unknown = call(HttpMethod.POST, "/api/v1/auth/login", null,
                Map.of("username", "nobody", "password", "whatever-password"));
        assertThat(unknown.getBody().get("code").asText()).as("no username enumeration").isEqualTo("INVALID_CREDENTIALS");

        String token = adminToken();
        JsonNode me = call(HttpMethod.GET, "/api/v1/auth/me", token, null).getBody();
        assertThat(me.get("username").asText()).isEqualTo("admin");
        assertThat(me.get("role").asText()).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'LOGIN_FAILED'", Integer.class))
                .isGreaterThanOrEqualTo(2);
        // Only a hash of the token is stored.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_tokens WHERE token_hash = ?", Integer.class, token))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'admin'", String.class))
                .startsWith("$2").doesNotContain(TestAuth.ADMIN_PASSWORD);
    }

    @Test
    @Order(3)
    void usersCannotUseAdminOperations() {
        String admin = adminToken();
        ensureAlice(admin);
        String alice = TestAuth.login(http, "alice", ALICE_PASSWORD);
        assertThat(call(HttpMethod.GET, "/api/v1/objects", alice, null).getStatusCode().value()).isEqualTo(200);
        for (String path : new String[] {"/api/v1/nodes", "/api/v1/repairs", "/api/v1/events", "/api/v1/stats",
                "/api/v1/overview", "/api/v1/admin/dlq"}) {
            ResponseEntity<JsonNode> res = call(HttpMethod.GET, path, alice, null);
            assertThat(res.getStatusCode().value()).as(path).isEqualTo(403);
            assertThat(res.getBody().get("code").asText()).isEqualTo("FORBIDDEN");
        }
        assertThat(call(HttpMethod.POST, "/api/v1/nodes/node-1/drain", alice, Map.of("reason", "x"))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/scrub", alice, null).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/users", alice,
                Map.of("username", "mallory", "password", "mallory-password-1", "role", "ADMIN"))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/v1/nodes", admin, null).getStatusCode().value()).isEqualTo(200);
        JsonNode overview = call(HttpMethod.GET, "/api/v1/overview", admin, null).getBody();
        assertThat(overview.get("nodesByState").has("HEALTHY")).isTrue();
        assertThat(overview.get("objects").has("storedBytes")).isTrue();
        assertThat(overview.get("events").has("outboxBacklog")).isTrue();
    }

    @Test
    @Order(4)
    void usersOnlySeeTheirOwnObjects() {
        String admin = adminToken();
        ensureAlice(admin);
        String alice = TestAuth.login(http, "alice", ALICE_PASSWORD);
        UUID adminObject = jdbc.queryForObject("""
                INSERT INTO objects (owner_id, name, total_size, chunk_size, chunk_count, status, replication_factor)
                VALUES ('admin', 'admins-secret.bin', 10, 1048576, 1, 'COMPLETE', 3) RETURNING id
                """, UUID.class);
        String path = "/api/v1/objects/" + adminObject;
        assertThat(call(HttpMethod.GET, path, admin, null).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, path, alice, null).getStatusCode().value()).as("not 403: no existence leak")
                .isEqualTo(404);
        assertThat(call(HttpMethod.GET, path + "/download", alice, null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.DELETE, path, alice, null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/v1/objects", alice, null).getBody().get("items").toString())
                .doesNotContain(adminObject.toString());
        assertThat(jdbc.queryForObject("SELECT status FROM objects WHERE id = ?", String.class, adminObject))
                .isEqualTo("COMPLETE");
    }

    @Test
    @Order(10)
    void replicaErrorsAreShownToAdminsOnly() {
        String admin = adminToken();
        ensureAlice(admin);
        String alice = TestAuth.login(http, "alice", ALICE_PASSWORD);
        UUID object = jdbc.queryForObject("""
                INSERT INTO objects (owner_id, name, total_size, chunk_size, chunk_count, status, replication_factor)
                VALUES ('alice', 'alices.bin', 10, 1048576, 1, 'COMPLETE', 3) RETURNING id
                """, UUID.class);
        UUID chunk = jdbc.queryForObject("INSERT INTO chunks (object_id, chunk_index, byte_offset, byte_size, status) "
                + "VALUES (?, 0, 0, 10, 'STORED') RETURNING id", UUID.class, object);
        jdbc.update("INSERT INTO storage_nodes (id, endpoint, state, protocol_version) VALUES ('sec-node', "
                + "'http://internal-host:9100', 'HEALTHY', 1) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO chunk_replicas (chunk_id, node_id, state, last_error) VALUES (?, 'sec-node', "
                + "'FAILED_WRITE', 'ConnectException: internal-host:9100 refused')", chunk);
        String asAlice = call(HttpMethod.GET, "/api/v1/objects/" + object, alice, null).getBody().toString();
        assertThat(asAlice).doesNotContain("internal-host").contains("hidden (operator detail)");
        // An admin viewing their own object sees the full error; insert one owned by admin.
        jdbc.update("UPDATE objects SET owner_id = 'admin' WHERE id = ?", object);
        assertThat(call(HttpMethod.GET, "/api/v1/objects/" + object, admin, null).getBody().toString())
                .contains("internal-host:9100 refused");
    }

    @Test
    @Order(5)
    void logoutAndExpiryInvalidateTokens() {
        String token = adminToken();
        assertThat(call(HttpMethod.GET, "/api/v1/auth/me", token, null).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/v1/auth/logout", token, null).getStatusCode().value()).isEqualTo(204);
        assertThat(call(HttpMethod.GET, "/api/v1/auth/me", token, null).getStatusCode().value()).isEqualTo(401);

        String second = adminToken();
        jdbc.update("UPDATE api_tokens SET expires_at = now() - interval '1 second' WHERE revoked_at IS NULL");
        assertThat(call(HttpMethod.GET, "/api/v1/auth/me", second, null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @Order(6)
    void weakPasswordsAndBadUsernamesAreRejected() {
        String admin = adminToken();
        ResponseEntity<JsonNode> weak = call(HttpMethod.POST, "/api/v1/admin/users", admin,
                Map.of("username", "bob", "password", "short", "role", "USER"));
        assertThat(weak.getStatusCode().value()).isEqualTo(400);
        assertThat(weak.getBody().get("code").asText()).isEqualTo("WEAK_PASSWORD");
        ResponseEntity<JsonNode> badName = call(HttpMethod.POST, "/api/v1/admin/users", admin,
                Map.of("username", "Bob/../x", "password", "long-enough-password", "role", "USER"));
        assertThat(badName.getBody().get("code").asText()).isEqualTo("INVALID_USERNAME");
        ensureAlice(admin);
        ResponseEntity<JsonNode> dup = call(HttpMethod.POST, "/api/v1/admin/users", admin,
                Map.of("username", "alice", "password", "another-password-1", "role", "USER"));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @Order(7)
    void responsesCarrySecurityHeadersAndOversizedBodiesAreRefused() {
        String admin = adminToken();
        ResponseEntity<JsonNode> res = call(HttpMethod.GET, "/api/v1/objects", admin, null);
        HttpHeaders h = res.getHeaders();
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(h.getFirst("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(h.getCacheControl()).contains("no-store");

        // Sent with chunked transfer encoding (no Content-Length), so the cap must hold while reading.
        HttpHeaders chunked = TestAuth.bearer(admin);
        chunked.setContentType(MediaType.APPLICATION_JSON);
        chunked.set("Idempotency-Key", "oversized-body-0001");
        ResponseEntity<JsonNode> big = http.exchange("/api/v1/uploads", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "x".repeat(70_000), "sizeBytes", 1), chunked), JsonNode.class);
        assertThat(big.getStatusCode().value()).as(String.valueOf(big.getBody())).isEqualTo(413);
        assertThat(big.getBody().get("code").asText()).isEqualTo("REQUEST_TOO_LARGE");

        // And with a declared Content-Length, it is refused before reading.
        HttpHeaders declared = TestAuth.bearer(admin);
        declared.setContentType(MediaType.APPLICATION_JSON);
        declared.set("Idempotency-Key", "oversized-body-0002");
        byte[] body = ("{\"name\":\"" + "y".repeat(70_000) + "\",\"sizeBytes\":1}").getBytes();
        declared.setContentLength(body.length);
        ResponseEntity<JsonNode> big2 = http.exchange("/api/v1/uploads", HttpMethod.POST, new HttpEntity<>(body, declared),
                JsonNode.class);
        assertThat(big2.getStatusCode().value()).as(String.valueOf(big2.getBody())).isEqualTo(413);
    }

    @Test
    @Order(8)
    void corsAllowsOnlyConfiguredOrigins() {
        HttpHeaders good = new HttpHeaders();
        good.setOrigin("http://localhost:5173");
        good.setAccessControlRequestMethod(HttpMethod.GET);
        ResponseEntity<String> ok = http.exchange("/api/v1/objects", HttpMethod.OPTIONS, new HttpEntity<>(good), String.class);
        assertThat(ok.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:5173");

        HttpHeaders evil = new HttpHeaders();
        evil.setOrigin("https://evil.example");
        evil.setAccessControlRequestMethod(HttpMethod.GET);
        ResponseEntity<String> denied = http.exchange("/api/v1/objects", HttpMethod.OPTIONS, new HttpEntity<>(evil), String.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(denied.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    @Order(9)
    void repeatedLoginFailuresForOneAccountAreRateLimited() {
        int status = 0;
        for (int i = 1; i <= 11; i++) {
            ResponseEntity<JsonNode> res = call(HttpMethod.POST, "/api/v1/auth/login", null,
                    Map.of("username", "victim-account", "password", "guess-number-" + i));
            status = res.getStatusCode().value();
            if (i <= 10) {
                assertThat(status).as("attempt %d", i).isEqualTo(401);
            }
        }
        assertThat(status).as("11th attempt within a minute").isEqualTo(429);
        // Other accounts from the same address are unaffected.
        assertThat(adminToken()).isNotBlank();
    }
}
