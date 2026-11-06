package dev.bytevault.node.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.bytevault.node.config.NodeProperties;
import dev.bytevault.node.storage.DataDirectory;
import dev.bytevault.protocol.NodeProtocol;
import dev.bytevault.protocol.RequestSigner;
import dev.bytevault.protocol.Sha256;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HeartbeatSenderTest {

    static final String SECRET = "h".repeat(40);

    @TempDir Path dir;

    @Test
    void sendsSignedHeartbeatWhoseBodyHashIsSigned() throws Exception {
        NodeProperties props = new NodeProperties("node-7", dir, SECRET, "http://node-7:9100", "http://cp:8090",
                Duration.ofSeconds(5), 1024);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://cp:8090");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        AtomicReference<MockClientHttpRequest> captured = new AtomicReference<>();

        server.expect(requestTo("http://cp:8090/internal/v1/nodes/node-7/heartbeat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(req -> captured.set((MockClientHttpRequest) req))
                .andRespond(withSuccess("{\"nodeId\":\"node-7\",\"state\":\"JOINING\"}", MediaType.APPLICATION_JSON));

        new HeartbeatSender(props, new DataDirectory(props), builder.build(), json, Clock.systemUTC()).send();
        server.verify();

        MockClientHttpRequest req = captured.get();
        byte[] body = req.getBodyAsBytes();
        var headers = req.getHeaders();
        assertThat(headers.getFirst(NodeProtocol.HEADER_CONTENT_SHA256)).isEqualTo(Sha256.of(body));
        assertThat(new RequestSigner(SECRET).verify("POST", "/internal/v1/nodes/node-7/heartbeat", "node-7",
                headers.getFirst(NodeProtocol.HEADER_TIMESTAMP), headers.getFirst(NodeProtocol.HEADER_CONTENT_SHA256),
                headers.getFirst(NodeProtocol.HEADER_SIGNATURE))).isEqualTo(RequestSigner.Verdict.VALID);

        JsonNode hb = json.readTree(body);
        assertThat(hb.get("nodeId").asText()).isEqualTo("node-7");
        assertThat(hb.get("status").asText()).isEqualTo("UP");
        assertThat(hb.get("dataDirWritable").asBoolean()).isTrue();
        assertThat(hb.get("capacityBytes").asLong()).isPositive();
        assertThat(new String(body)).doesNotContain(SECRET);
    }

    @Test
    void failureIsCountedNotThrown() {
        NodeProperties props = new NodeProperties("node-7", dir, SECRET, "http://node-7:9100", "http://cp:8090",
                Duration.ofSeconds(5), 1024);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://cp:8090");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://cp:8090/internal/v1/nodes/node-7/heartbeat")).andRespond(withServerError());

        HeartbeatSender sender = new HeartbeatSender(props, new DataDirectory(props), builder.build(),
                new ObjectMapper().registerModule(new JavaTimeModule()), Clock.systemUTC());
        sender.send();

        assertThat(sender.consecutiveFailures()).isEqualTo(1);
    }
}
