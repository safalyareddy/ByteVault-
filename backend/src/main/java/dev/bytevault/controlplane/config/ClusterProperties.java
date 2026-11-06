package dev.bytevault.controlplane.config;

import dev.bytevault.protocol.NodeProtocol;
import dev.bytevault.protocol.RequestSigner;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Storage-node membership and data-path limits.
 *
 * <p>{@code nodes} is {@code id=endpoint,...}; {@code nodeSecrets} is {@code id:secret,...} (the
 * per-node HMAC secrets, ADR-0002). Both are parsed eagerly so a malformed value fails startup.
 * Secrets never appear in {@link #toString()}.
 */
@Validated
@ConfigurationProperties(prefix = "bytevault.cluster")
public class ClusterProperties {

    private String nodes = "";
    private String nodeSecrets = "";
    @NotNull private Duration nodeRequestTimeout = Duration.ofSeconds(30);
    @Min(1) @Max(64) private int maxConcurrentChunkTransfers = 8;
    @NotNull private Duration transferPermitWait = Duration.ofSeconds(30);
    @NotNull private Duration uploadSessionTtl = Duration.ofHours(24);
    @NotNull private Path spoolDir = Path.of(System.getProperty("java.io.tmpdir"), "bytevault-spool");

    public record NodeConfig(String id, URI endpoint, RequestSigner signer) {
        @Override
        public String toString() {
            return "NodeConfig[id=" + id + ", endpoint=" + endpoint + "]";
        }
    }

    /** Configured nodes by ID, in declaration order. */
    public Map<String, NodeConfig> parsedNodes() {
        Map<String, String> endpoints = parse(nodes, '=', "bytevault.cluster.nodes");
        Map<String, String> secrets = parse(nodeSecrets, ':', "bytevault.cluster.node-secrets");
        Map<String, NodeConfig> result = new LinkedHashMap<>();
        endpoints.forEach((id, endpoint) -> {
            if (!NodeProtocol.NODE_ID.matcher(id).matches()) {
                throw new IllegalStateException("invalid node id in bytevault.cluster.nodes: " + id);
            }
            String secret = secrets.get(id);
            if (secret == null || secret.length() < RequestSigner.MIN_SECRET_LENGTH) {
                throw new IllegalStateException("missing or too-short secret for node " + id
                        + " in bytevault.cluster.node-secrets");
            }
            URI uri = URI.create(endpoint);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new IllegalStateException("node endpoint must be http(s): " + id);
            }
            result.put(id, new NodeConfig(id, uri, new RequestSigner(secret)));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> parse(String raw, char separator, String name) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String entry : raw.split(",")) {
            String e = entry.strip();
            int i = e.indexOf(separator);
            if (i <= 0 || i == e.length() - 1) {
                // Never echo the entry: it may contain a secret.
                throw new IllegalStateException("malformed entry in " + name);
            }
            if (out.put(e.substring(0, i).strip(), e.substring(i + 1).strip()) != null) {
                throw new IllegalStateException("duplicate node id in " + name);
            }
        }
        return out;
    }

    public String getNodes() { return nodes; }
    public void setNodes(String nodes) { this.nodes = nodes; }
    public void setNodeSecrets(String nodeSecrets) { this.nodeSecrets = nodeSecrets; }
    public Duration getNodeRequestTimeout() { return nodeRequestTimeout; }
    public void setNodeRequestTimeout(Duration v) { this.nodeRequestTimeout = v; }
    public int getMaxConcurrentChunkTransfers() { return maxConcurrentChunkTransfers; }
    public void setMaxConcurrentChunkTransfers(int v) { this.maxConcurrentChunkTransfers = v; }
    public Duration getTransferPermitWait() { return transferPermitWait; }
    public void setTransferPermitWait(Duration v) { this.transferPermitWait = v; }
    public Duration getUploadSessionTtl() { return uploadSessionTtl; }
    public void setUploadSessionTtl(Duration v) { this.uploadSessionTtl = v; }
    public Path getSpoolDir() { return spoolDir; }
    public void setSpoolDir(Path v) { this.spoolDir = v; }

    @Override
    public String toString() {
        return "ClusterProperties[nodes=" + nodes + ", nodeRequestTimeout=" + nodeRequestTimeout
                + ", maxConcurrentChunkTransfers=" + maxConcurrentChunkTransfers + "]";
    }
}
