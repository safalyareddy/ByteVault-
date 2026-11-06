package dev.bytevault.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/** Logs a TestRestTemplate in once and attaches the bearer token to requests that set none. */
public final class TestAuth {

    public static final String ADMIN = "admin";
    public static final String ADMIN_PASSWORD = "IntegrationTest-Admin-Pass-0001";

    private TestAuth() {}

    record BearerInterceptor(String token) implements ClientHttpRequestInterceptor {
        @Override
        public org.springframework.http.client.ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request, byte[] body,
                org.springframework.http.client.ClientHttpRequestExecution execution) throws java.io.IOException {
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(token);
            }
            return execution.execute(request, body);
        }
    }

    /** Idempotent per TestRestTemplate: logs in as admin only the first time. */
    public static void asAdmin(TestRestTemplate http) {
        if (http.getRestTemplate().getInterceptors().stream().noneMatch(i -> i instanceof BearerInterceptor)) {
            http.getRestTemplate().getInterceptors().add(new BearerInterceptor(login(http, ADMIN, ADMIN_PASSWORD)));
        }
    }

    public static String login(TestRestTemplate http, String username, String password) {
        var res = http.postForEntity("/api/v1/auth/login", Map.of("username", username, "password", password),
                JsonNode.class);
        if (res.getStatusCode().value() != 200) {
            throw new IllegalStateException("login failed for " + username + ": " + res.getBody());
        }
        return res.getBody().get("token").asText();
    }

    /** Headers that bypass the interceptor's token: an explicit (possibly invalid) bearer. */
    public static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
