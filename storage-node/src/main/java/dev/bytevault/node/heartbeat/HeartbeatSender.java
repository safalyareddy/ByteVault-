package dev.bytevault.node.heartbeat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytevault.node.config.NodeProperties;
import dev.bytevault.node.storage.DataDirectory;
import dev.bytevault.protocol.Heartbeat;
import dev.bytevault.protocol.NodeProtocol;
import dev.bytevault.protocol.RequestSigner;
import dev.bytevault.protocol.Sha256;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends a signed heartbeat every {@code bytevault.node.heartbeat-interval} (ADR-0010). The exact
 * body bytes are hashed and that hash is signed, so the control plane authenticates the payload.
 * Values are measured fresh for every beat.
 */
@Component
public class HeartbeatSender {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatSender.class);

    private final NodeProperties props;
    private final DataDirectory dataDirectory;
    private final RequestSigner signer;
    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile String lastReportedState;

    public HeartbeatSender(NodeProperties props, DataDirectory dataDirectory, RestClient controlPlaneClient,
                           ObjectMapper json, Clock clock) {
        this.props = props;
        this.dataDirectory = dataDirectory;
        this.signer = new RequestSigner(props.authSecret(), clock);
        this.http = controlPlaneClient;
        this.json = json;
        this.clock = clock;
    }

    /** HTTP client for the control plane with short timeouts: a slow beat is a missed beat. */
    @org.springframework.context.annotation.Configuration
    static class ClientConfig {
        @org.springframework.context.annotation.Bean
        RestClient controlPlaneClient(RestClient.Builder builder, NodeProperties props) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(2));
            factory.setReadTimeout(Duration.ofSeconds(3));
            return builder.baseUrl(props.controlPlaneUrl()).requestFactory(factory).build();
        }
    }

    public record HeartbeatAck(String nodeId, String state) {}

    @Scheduled(initialDelay = 0, fixedDelayString = "${bytevault.node.heartbeat-interval}")
    public void send() {
        boolean writable = dataDirectory.isWritable();
        DataDirectory.DiskUsage usage = dataDirectory.usage();
        Heartbeat hb = new Heartbeat(props.id(), NodeProtocol.VERSION, props.advertisedEndpoint(),
                writable ? "UP" : "DOWN", writable, usage.totalBytes(), usage.usableBytes(), clock.instant());
        byte[] body;
        try {
            body = json.writeValueAsBytes(hb);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String path = NodeProtocol.heartbeatPath(props.id());
        RequestSigner.SignedHeaders h = signer.sign("POST", path, props.id(), Sha256.of(body));
        try {
            HeartbeatAck ack = http.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(NodeProtocol.HEADER_NODE_ID, h.nodeId())
                    .header(NodeProtocol.HEADER_TIMESTAMP, h.timestamp())
                    .header(NodeProtocol.HEADER_CONTENT_SHA256, h.contentSha256())
                    .header(NodeProtocol.HEADER_SIGNATURE, h.signature())
                    .body(body)
                    .retrieve()
                    .body(HeartbeatAck.class);
            int failures = consecutiveFailures.getAndSet(0);
            if (failures > 0) {
                log.info("Heartbeat delivered after {} failed attempt(s)", failures);
            }
            String state = ack == null ? null : ack.state();
            if (state != null && !state.equals(lastReportedState)) {
                log.info("Control plane reports this node as {}", state);
                lastReportedState = state;
            }
        } catch (RestClientException e) {
            int failures = consecutiveFailures.incrementAndGet();
            // Log the first failure and then every 12th, so an outage doesn't flood the log.
            if (failures == 1 || failures % 12 == 0) {
                log.warn("Heartbeat to control plane failed ({} consecutive): {}", failures, e.getMessage());
            }
        }
    }

    int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
