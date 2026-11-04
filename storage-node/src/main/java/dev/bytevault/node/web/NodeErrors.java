package dev.bytevault.node.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.ResponseEntity;

/** Error body shared by node endpoints and the auth filter: {@code {code, message, timestamp}}. */
final class NodeErrors {

    record NodeError(String code, String message, Instant timestamp) {}

    private NodeErrors() {}

    static ResponseEntity<NodeError> entity(int status, String code, String message) {
        return ResponseEntity.status(status).body(new NodeError(code, message, Instant.now()));
    }

    static void write(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        // Fixed codes and messages only; nothing from the request is echoed.
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"timestamp\":\""
                + Instant.now() + "\"}");
    }
}
