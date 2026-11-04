package dev.bytevault.controlplane.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytevault.controlplane.config.ClusterProperties.NodeConfig;
import dev.bytevault.controlplane.web.ApiException;
import dev.bytevault.protocol.Heartbeat;
import dev.bytevault.protocol.NodeProtocol;
import dev.bytevault.protocol.RequestSigner;
import dev.bytevault.protocol.Sha256;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives signed node heartbeats (ADR-0010). Every authentication failure is a bare 401; the
 * reason goes to the log and the {@code bv_heartbeats_rejected_total} counter only.
 */
@RestController
public class HeartbeatController {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatController.class);
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private final NodeClient nodeClient;
    private final NodeLifecycleService lifecycle;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public HeartbeatController(NodeClient nodeClient, NodeLifecycleService lifecycle, ObjectMapper json,
                               MeterRegistry meters) {
        this.nodeClient = nodeClient;
        this.lifecycle = lifecycle;
        this.json = json;
        this.meters = meters;
    }

    public record HeartbeatAck(String nodeId, String state) {}

    @PostMapping(NodeProtocol.PREFIX + "/nodes/{nodeId}/heartbeat")
    public HeartbeatAck heartbeat(@PathVariable String nodeId, HttpServletRequest req) throws IOException {
        byte[] body;
        try (InputStream in = req.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw reject("body_too_large", nodeId);
        }
        NodeConfig node = nodeClient.configuredNodes().get(nodeId);
        if (node == null) {
            throw reject("unknown_node", nodeId);
        }
        String headerNode = req.getHeader(NodeProtocol.HEADER_NODE_ID);
        String declaredSha = req.getHeader(NodeProtocol.HEADER_CONTENT_SHA256);
        RequestSigner.Verdict verdict = node.signer().verify("POST", req.getRequestURI(), headerNode,
                req.getHeader(NodeProtocol.HEADER_TIMESTAMP), declaredSha, req.getHeader(NodeProtocol.HEADER_SIGNATURE));
        if (verdict != RequestSigner.Verdict.VALID) {
            throw reject(verdict.name().toLowerCase(), nodeId);
        }
        if (!nodeId.equals(headerNode)) {
            throw reject("node_id_mismatch", nodeId);
        }
        if (!Sha256.of(body).equals(declaredSha)) {
            throw reject("body_checksum_mismatch", nodeId);
        }
        Heartbeat hb;
        try {
            hb = json.readValue(body, Heartbeat.class);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_HEARTBEAT", "heartbeat body is not valid JSON");
        }
        if (!nodeId.equals(hb.nodeId())) {
            throw reject("body_node_id_mismatch", nodeId);
        }
        return new HeartbeatAck(nodeId, lifecycle.onHeartbeat(hb).name());
    }

    private ApiException reject(String reason, String nodeId) {
        meters.counter("bv_heartbeats_rejected_total", "reason", reason).increment();
        log.warn("Rejected heartbeat for node path id '{}': {}",
                nodeId.length() > 40 ? nodeId.substring(0, 40) + "…" : nodeId, reason);
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "authentication failed");
    }
}
