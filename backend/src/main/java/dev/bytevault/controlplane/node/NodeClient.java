package dev.bytevault.controlplane.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytevault.controlplane.config.ClusterProperties;
import dev.bytevault.controlplane.config.ClusterProperties.NodeConfig;
import dev.bytevault.protocol.NodeProtocol;
import dev.bytevault.protocol.RequestSigner;
import dev.bytevault.protocol.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Signed HTTP client for storage-node protocol v1. Bodies are streamed from and to files; no chunk
 * is ever held whole in memory. Every byte read from a node is re-hashed before it is trusted.
 */
@Component
public class NodeClient {

    private static final int BUFFER = 64 * 1024;

    private final Map<String, NodeConfig> nodes;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final ObjectMapper json;

    public NodeClient(ClusterProperties cluster, ObjectMapper json) {
        this.nodes = cluster.parsedNodes();
        this.requestTimeout = cluster.getNodeRequestTimeout();
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public Map<String, NodeConfig> configuredNodes() {
        return nodes;
    }

    public record PutResult(boolean success, String sha256, String error) {}

    /** Uploads a spooled chunk. Success requires the node's echoed checksum to equal ours. */
    public PutResult putChunk(String nodeId, UUID chunkId, Path file, String sha256) {
        try {
            String path = NodeProtocol.chunkPath(chunkId);
            HttpRequest req = signed(nodeId, "PUT", path, sha256)
                    .header("Content-Type", "application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 200 || res.statusCode() == 201) {
                String echoed = json.readTree(res.body()).path("sha256").asText();
                return echoed.equals(sha256)
                        ? new PutResult(true, echoed, null)
                        : new PutResult(false, echoed, "node acknowledged a different checksum");
            }
            return new PutResult(false, null, "HTTP " + res.statusCode() + ": " + errorCode(res.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PutResult(false, null, "interrupted");
        } catch (IOException e) {
            return new PutResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public enum FetchStatus { OK, CORRUPT, MISSING, UNAVAILABLE }

    public record FetchResult(FetchStatus status, String actualSha256, String error) {}

    /**
     * Downloads a chunk into {@code dest}, hashing as it streams. Returns OK only when the byte count
     * and SHA-256 both match what metadata expects. The caller must discard {@code dest} otherwise.
     */
    public FetchResult fetchChunk(String nodeId, UUID chunkId, long expectedSize, String expectedSha256, Path dest) {
        try {
            HttpRequest req = signed(nodeId, "GET", NodeProtocol.chunkPath(chunkId), NodeProtocol.NO_BODY).GET().build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                if (res.statusCode() == 404) {
                    return new FetchResult(FetchStatus.MISSING, null, "chunk not found on node");
                }
                if (res.statusCode() != 200) {
                    return new FetchResult(FetchStatus.UNAVAILABLE, null, "HTTP " + res.statusCode());
                }
                MessageDigest digest = Sha256.newDigest();
                long total = 0;
                try (OutputStream out = Files.newOutputStream(dest)) {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > expectedSize) {
                            return new FetchResult(FetchStatus.CORRUPT, null, "more bytes than expected");
                        }
                        digest.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
                String actual = Sha256.hex(digest.digest());
                if (total != expectedSize || !actual.equals(expectedSha256)) {
                    return new FetchResult(FetchStatus.CORRUPT, actual,
                            "expected " + expectedSize + " bytes sha256 " + expectedSha256 + ", got " + total
                                    + " bytes sha256 " + actual);
                }
                return new FetchResult(FetchStatus.OK, actual, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult(FetchStatus.UNAVAILABLE, null, "interrupted");
        } catch (IOException e) {
            return new FetchResult(FetchStatus.UNAVAILABLE, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public record VerifyResult(FetchStatus status, String actualSha256, long sizeBytes, String error) {
        /** True only if the node read the bytes back and they match what metadata expects. */
        public boolean matches(String expectedSha256, long expectedSize) {
            return status == FetchStatus.OK && expectedSha256.equals(actualSha256) && sizeBytes == expectedSize;
        }
    }

    /**
     * Asks a node to re-hash a chunk from disk (scrubber, repair target check, reconciliation).
     * Status OK means the node answered; the caller compares the hash with metadata.
     */
    public VerifyResult verifyChunk(String nodeId, UUID chunkId) {
        try {
            HttpRequest req = signed(nodeId, "POST", NodeProtocol.verifyPath(chunkId), NodeProtocol.NO_BODY)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 404) {
                return new VerifyResult(FetchStatus.MISSING, null, 0, "chunk not found on node");
            }
            if (res.statusCode() != 200) {
                return new VerifyResult(FetchStatus.UNAVAILABLE, null, 0, "HTTP " + res.statusCode());
            }
            var body = json.readTree(res.body());
            return new VerifyResult(FetchStatus.OK, body.path("actualSha256").asText(), body.path("sizeBytes").asLong(),
                    null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new VerifyResult(FetchStatus.UNAVAILABLE, null, 0, "interrupted");
        } catch (IOException e) {
            return new VerifyResult(FetchStatus.UNAVAILABLE, null, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public enum ProbeResult { AUTHENTICATED, REJECTED, UNREACHABLE }

    /**
     * Admission probe (ADR-0010): a signed GET of a chunk ID that is never allocated. A 404 proves
     * the configured endpoint is reachable and that both sides hold the same secret.
     */
    public ProbeResult admissionProbe(String nodeId) {
        try {
            HttpRequest req = signed(nodeId, "GET", NodeProtocol.chunkPath(NodeProtocol.PROBE_CHUNK_ID),
                    NodeProtocol.NO_BODY).timeout(Duration.ofSeconds(2)).GET().build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 404 ? ProbeResult.AUTHENTICATED : ProbeResult.REJECTED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.UNREACHABLE;
        } catch (IOException e) {
            return ProbeResult.UNREACHABLE;
        }
    }

    /** Idempotent delete. Returns true when the node confirmed the chunk is absent. */
    public boolean deleteChunk(String nodeId, UUID chunkId) {
        try {
            HttpRequest req = signed(nodeId, "DELETE", NodeProtocol.chunkPath(chunkId), NodeProtocol.NO_BODY)
                    .DELETE().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 204;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private HttpRequest.Builder signed(String nodeId, String method, String path, String contentSha256) {
        NodeConfig node = node(nodeId);
        RequestSigner.SignedHeaders h = node.signer().sign(method, path, nodeId, contentSha256);
        return HttpRequest.newBuilder(URI.create(node.endpoint() + path))
                .timeout(requestTimeout)
                .header(NodeProtocol.HEADER_NODE_ID, h.nodeId())
                .header(NodeProtocol.HEADER_TIMESTAMP, h.timestamp())
                .header(NodeProtocol.HEADER_CONTENT_SHA256, h.contentSha256())
                .header(NodeProtocol.HEADER_SIGNATURE, h.signature());
    }

    private NodeConfig node(String nodeId) {
        NodeConfig node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("node not configured: " + nodeId);
        }
        return node;
    }

    private String errorCode(byte[] body) {
        try {
            return json.readTree(body).path("code").asText("unknown");
        } catch (IOException e) {
            return "unparseable error body";
        }
    }
}
