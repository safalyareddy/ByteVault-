package dev.bytevault.node.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.bytevault.protocol.NodeProtocol;
import dev.bytevault.protocol.RequestSigner;
import dev.bytevault.protocol.Sha256;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class ChunkControllerTest {

    static final String SECRET = "c".repeat(48);
    static final String NODE = "node-test";

    @TempDir static Path dataDir;

    @DynamicPropertySource
    static void nodeProps(DynamicPropertyRegistry r) {
        r.add("bytevault.node.id", () -> NODE);
        r.add("bytevault.node.data-dir", () -> dataDir.toString());
        r.add("bytevault.node.auth-secret", () -> SECRET);
        r.add("bytevault.node.max-chunk-bytes", () -> "1048576");
    }

    @Autowired MockMvc mvc;
    final RequestSigner signer = new RequestSigner(SECRET);

    MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder b, String method, String path, String node, String sha) {
        var h = signer.sign(method, path, node, sha);
        return b.header(NodeProtocol.HEADER_NODE_ID, h.nodeId())
                .header(NodeProtocol.HEADER_TIMESTAMP, h.timestamp())
                .header(NodeProtocol.HEADER_CONTENT_SHA256, h.contentSha256())
                .header(NodeProtocol.HEADER_SIGNATURE, h.signature());
    }

    @Test
    void signedPutGetDeleteRoundTrip() throws Exception {
        byte[] data = new byte[70_000];
        new Random(9).nextBytes(data);
        String sha = Sha256.of(data);
        String path = NodeProtocol.chunkPath(UUID.randomUUID());

        mvc.perform(signed(put(path).content(data), "PUT", path, NODE, sha))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sha256").value(sha))
                .andExpect(jsonPath("$.sizeBytes").value(data.length));
        mvc.perform(signed(put(path).content(data), "PUT", path, NODE, sha))
                .andExpect(status().isOk());

        var res = mvc.perform(signed(get(path), "GET", path, NODE, NodeProtocol.NO_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string(NodeProtocol.HEADER_STORED_SHA256, sha))
                .andReturn();
        assertThat(res.getResponse().getContentAsByteArray()).isEqualTo(data);

        mvc.perform(signed(delete(path), "DELETE", path, NODE, NodeProtocol.NO_BODY)).andExpect(status().isNoContent());
        mvc.perform(signed(get(path), "GET", path, NODE, NodeProtocol.NO_BODY)).andExpect(status().isNotFound());
    }

    @Test
    void unsignedOrForeignRequestsRejected() throws Exception {
        String path = NodeProtocol.chunkPath(UUID.randomUUID());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(signed(get(path), "GET", path, "node-other", NodeProtocol.NO_BODY)).andExpect(status().isForbidden());
        var otherSigner = new RequestSigner("d".repeat(48));
        var h = otherSigner.sign("GET", path, NODE, NodeProtocol.NO_BODY);
        mvc.perform(get(path).header(NodeProtocol.HEADER_NODE_ID, NODE).header(NodeProtocol.HEADER_TIMESTAMP, h.timestamp())
                        .header(NodeProtocol.HEADER_CONTENT_SHA256, NodeProtocol.NO_BODY)
                        .header(NodeProtocol.HEADER_SIGNATURE, h.signature()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthStaysUnauthenticated() throws Exception {
        mvc.perform(get(NodeProtocol.HEALTH_PATH)).andExpect(status().isOk());
    }

    @Test
    void validatesIdLengthAndChecksum() throws Exception {
        String bad = NodeProtocol.CHUNKS_PATH + "/not-a-uuid";
        mvc.perform(signed(get(bad), "GET", bad, NODE, NodeProtocol.NO_BODY)).andExpect(status().isBadRequest());

        String path = NodeProtocol.chunkPath(UUID.randomUUID());
        byte[] big = new byte[1_048_577];
        mvc.perform(signed(put(path).content(big), "PUT", path, NODE, Sha256.of(big)))
                .andExpect(status().isPayloadTooLarge());

        byte[] data = "hello".getBytes();
        mvc.perform(signed(put(path).content(data), "PUT", path, NODE, "0".repeat(64)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
    }
}
