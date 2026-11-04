package dev.bytevault.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytevault.protocol.Sha256;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 2 gate: real uploads and downloads through the control plane against three independent
 * storage-node containers built from storage-node/Dockerfile, each with its own identity, secret
 * and filesystem. Assertions check node disks directly, not just metadata.
 *
 * <p>Requires {@code storage-node/target/bytevault-storage-node.jar}; the reactor builds
 * storage-node before backend, so {@code mvn verify} from the root satisfies this.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.datasource.password=test",
        "bytevault.chunk-size-bytes=1048576",
        "bytevault.heartbeat-interval=1s",
        "bytevault.suspect-after=3s",
        "bytevault.offline-after=6s",
        "bytevault.deletion-interval=1s",
        "bytevault.cluster.node-request-timeout=10s",
        "bytevault.cluster.admission-heartbeats=2",
        "bytevault.failure-detector-interval=500ms"})
@Import(ContainersConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorageIT {

    static final int CHUNK = 1024 * 1024;
    static final String DATA_DIR = "/var/lib/bytevault/node";
    /** Secrets the control plane is configured with. */
    static final Map<String, String> SECRETS = Map.of("node-1", "1".repeat(48), "node-2", "2".repeat(48),
            "node-3", "3".repeat(48), "node-4", "4".repeat(48), "node-5", "5".repeat(48));
    /** node-4 runs with a different secret than configured: its heartbeats must be rejected. */
    static final String NODE_4_ACTUAL_SECRET = "x".repeat(48);
    static final List<String> GOOD_NODES = List.of("node-1", "node-2", "node-3");
    static final Map<String, GenericContainer<?>> NODES = new LinkedHashMap<>();
    static final Map<String, GenericContainer<?>> MISCONFIGURED = new LinkedHashMap<>();
    static final int CONTROL_PLANE_PORT = freePort();

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            // Nodes push heartbeats to this JVM's control plane through the Testcontainers host tunnel.
            org.testcontainers.Testcontainers.exposeHostPorts(CONTROL_PLANE_PORT);
            ImageFromDockerfile image = new ImageFromDockerfile("bytevault-storage-node-it", false)
                    .withFileFromPath("Dockerfile", Path.of("../storage-node/Dockerfile"))
                    .withFileFromPath("target/bytevault-storage-node.jar",
                            Path.of("../storage-node/target/bytevault-storage-node.jar"));
            for (String id : List.of("node-1", "node-2", "node-3", "node-4", "node-5")) {
                GenericContainer<?> c = new GenericContainer<>(image)
                        .withEnv("BV_NODE_ID", id)
                        .withEnv("BV_NODE_AUTH_SECRET", id.equals("node-4") ? NODE_4_ACTUAL_SECRET : SECRETS.get(id))
                        .withEnv("BV_CONTROL_PLANE_URL", "http://host.testcontainers.internal:" + CONTROL_PLANE_PORT)
                        .withEnv("BV_HEARTBEAT_INTERVAL", "1s")
                        .withExposedPorts(9100)
                        .waitingFor(Wait.forHttp("/internal/v1/health").forStatusCode(200)
                                .withStartupTimeout(Duration.ofSeconds(90)));
                c.start();
                (GOOD_NODES.contains(id) ? NODES : MISCONFIGURED).put(id, c);
            }
        }
    }

    static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void cluster(DynamicPropertyRegistry r) {
        StringBuilder nodes = new StringBuilder();
        StringBuilder secrets = new StringBuilder();
        NODES.forEach((id, c) -> {
            nodes.append(nodes.isEmpty() ? "" : ",").append(id).append("=http://")
                    .append(c.getHost()).append(':').append(c.getMappedPort(9100));
            secrets.append(secrets.isEmpty() ? "" : ",").append(id).append(':').append(SECRETS.get(id));
        });
        // node-4: real endpoint, wrong secret. node-5: right secret, endpoint nothing listens on.
        GenericContainer<?> node4 = MISCONFIGURED.get("node-4");
        if (node4 != null) {
            nodes.append(",node-4=http://").append(node4.getHost()).append(':').append(node4.getMappedPort(9100));
            nodes.append(",node-5=http://127.0.0.1:1");
            secrets.append(",node-4:").append(SECRETS.get("node-4")).append(",node-5:").append(SECRETS.get("node-5"));
        }
        r.add("server.port", () -> CONTROL_PLANE_PORT);
        r.add("bytevault.cluster.nodes", nodes::toString);
        r.add("bytevault.cluster.node-secrets", secrets::toString);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    // ------------------------------------------------------------------ helpers

    static byte[] randomBytes(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    void awaitHealthyNodes(int count) {
        await("nodes HEALTHY", Duration.ofSeconds(45), () -> {
            Integer healthy = jdbc.queryForObject("SELECT count(*) FROM storage_nodes WHERE state = 'HEALTHY'", Integer.class);
            return healthy != null && healthy >= count;
        });
    }

    static void await(String what, Duration timeout, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    ResponseEntity<JsonNode> createUpload(String key, String name, long size) {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", key);
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("name", name, "sizeBytes", size, "contentType", "application/octet-stream");
        return http.exchange("/api/v1/uploads", HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    ResponseEntity<JsonNode> putChunk(String uploadId, int index, byte[] bytes, String sha) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        if (sha != null) {
            h.set("X-Chunk-SHA256", sha);
        }
        return http.exchange("/api/v1/uploads/" + uploadId + "/chunks/" + index, HttpMethod.PUT,
                new HttpEntity<>(bytes, h), JsonNode.class);
    }

    static byte[] slice(byte[] data, int index) {
        int from = index * CHUNK;
        return java.util.Arrays.copyOfRange(data, from, Math.min(data.length, from + CHUNK));
    }

    /** Uploads every chunk and completes; returns the object ID. */
    String uploadAll(byte[] data, String key) {
        ResponseEntity<JsonNode> created = createUpload(key, "file-" + key + ".bin", data.length);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String uploadId = created.getBody().get("uploadId").asText();
        int chunks = created.getBody().get("chunkCount").asInt();
        for (int i = 0; i < chunks; i++) {
            byte[] part = slice(data, i);
            ResponseEntity<JsonNode> res = putChunk(uploadId, i, part, Sha256.of(part));
            assertThat(res.getStatusCode().value()).as("chunk %d: %s", i, res.getBody()).isEqualTo(200);
            assertThat(res.getBody().get("verifiedReplicas").asInt()).isEqualTo(3);
        }
        ResponseEntity<JsonNode> done = http.postForEntity("/api/v1/uploads/" + uploadId + "/complete", null, JsonNode.class);
        assertThat(done.getStatusCode().value()).as("complete: %s", done.getBody()).isEqualTo(200);
        assertThat(done.getBody().get("status").asText()).isEqualTo("COMPLETE");
        return done.getBody().get("objectId").asText();
    }

    JsonNode detail(String objectId) {
        return http.getForEntity("/api/v1/objects/" + objectId, JsonNode.class).getBody();
    }

    static String chunkPath(String chunkId) {
        return DATA_DIR + "/chunks/" + chunkId.substring(0, 2) + "/" + chunkId.substring(2, 4) + "/" + chunkId + ".chunk";
    }

    /** SHA-256 of the chunk file as it sits on the node's own disk, or null if absent. */
    static String shaOnNode(String nodeId, String chunkId) throws Exception {
        var r = NODES.get(nodeId).execInContainer("sh", "-c", "sha256sum " + chunkPath(chunkId) + " 2>/dev/null");
        return r.getExitCode() == 0 ? r.getStdout().split("\\s+")[0] : null;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void uploadAndDownloadIsByteIdenticalWithThreeVerifiedReplicasOnDistinctNodes() throws Exception {
        awaitHealthyNodes(3);
        byte[] data = randomBytes(3 * CHUNK + 12_345, 100); // 4 chunks, last one partial
        String objectId = uploadAll(data, "e2e-roundtrip-0001");

        ResponseEntity<byte[]> download = http.getForEntity("/api/v1/objects/" + objectId + "/download", byte[].class);
        assertThat(download.getStatusCode().value()).isEqualTo(200);
        assertThat(download.getHeaders().getContentLength()).isEqualTo(data.length);
        assertThat(Sha256.of(download.getBody())).isEqualTo(Sha256.of(data));

        JsonNode detail = detail(objectId);
        assertThat(detail.get("object").get("status").asText()).isEqualTo("COMPLETE");
        assertThat(detail.get("chunks")).hasSize(4);
        for (JsonNode chunk : detail.get("chunks")) {
            Set<String> holders = new HashSet<>();
            for (JsonNode r : chunk.get("replicas")) {
                assertThat(r.get("state").asText()).isEqualTo("VERIFIED");
                holders.add(r.get("nodeId").asText());
            }
            assertThat(holders).as("distinct nodes").containsExactlyInAnyOrder("node-1", "node-2", "node-3");
            // The bytes really are on each node's own filesystem with the recorded checksum.
            for (String node : holders) {
                assertThat(shaOnNode(node, chunk.get("chunkId").asText()))
                        .as("chunk %s on %s", chunk.get("index"), node)
                        .isEqualTo(chunk.get("sha256").asText());
            }
        }
    }

    @Test
    @Order(2)
    void retriesHaveNoDuplicateEffects() {
        awaitHealthyNodes(3);
        byte[] data = randomBytes(CHUNK + 10, 200);
        ResponseEntity<JsonNode> first = createUpload("retry-key-0001", "retry.bin", data.length);
        ResponseEntity<JsonNode> second = createUpload("retry-key-0001", "retry.bin", data.length);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().get("replayed").asBoolean()).isTrue();
        String uploadId = first.getBody().get("uploadId").asText();
        assertThat(second.getBody().get("uploadId").asText()).isEqualTo(uploadId);

        ResponseEntity<JsonNode> reused = createUpload("retry-key-0001", "retry.bin", data.length + 1);
        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(reused.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        for (int attempt = 0; attempt < 3; attempt++) {
            for (int i = 0; i < 2; i++) {
                assertThat(putChunk(uploadId, i, slice(data, i), null).getStatusCode().value()).isEqualTo(200);
            }
        }
        String objectId = first.getBody().get("objectId").asText();
        Integer replicaRows = jdbc.queryForObject("SELECT count(*) FROM chunk_replicas r JOIN chunks c ON c.id = r.chunk_id "
                + "WHERE c.object_id = ?::uuid", Integer.class, objectId);
        assertThat(replicaRows).as("2 chunks x RF 3, no duplicates").isEqualTo(6);
        Integer objects = jdbc.queryForObject("SELECT count(*) FROM upload_sessions WHERE idempotency_key = 'retry-key-0001'",
                Integer.class);
        assertThat(objects).isEqualTo(1);

        for (int attempt = 0; attempt < 2; attempt++) {
            ResponseEntity<JsonNode> done = http.postForEntity("/api/v1/uploads/" + uploadId + "/complete", null, JsonNode.class);
            assertThat(done.getStatusCode().value()).isEqualTo(200);
            assertThat(done.getBody().get("status").asText()).isEqualTo("COMPLETE");
        }
        Integer completedEvents = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'ObjectCompleted'", Integer.class, objectId);
        assertThat(completedEvents).isEqualTo(1);
    }

    @Test
    @Order(3)
    void incompleteUploadsAreRejectedAndNotDownloadable() {
        awaitHealthyNodes(3);
        byte[] data = randomBytes(CHUNK * 2, 300);
        ResponseEntity<JsonNode> created = createUpload("partial-key-0001", "partial.bin", data.length);
        String uploadId = created.getBody().get("uploadId").asText();
        String objectId = created.getBody().get("objectId").asText();
        assertThat(putChunk(uploadId, 0, slice(data, 0), null).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> done = http.postForEntity("/api/v1/uploads/" + uploadId + "/complete", null, JsonNode.class);
        assertThat(done.getStatusCode().value()).isEqualTo(409);
        assertThat(done.getBody().get("code").asText()).isEqualTo("INCOMPLETE_UPLOAD");
        assertThat(done.getBody().toString()).contains("missingChunkIndexes", "[1]");

        ResponseEntity<JsonNode> download = http.getForEntity("/api/v1/objects/" + objectId + "/download", JsonNode.class);
        assertThat(download.getStatusCode().value()).isEqualTo(409);
        assertThat(detail(objectId).get("object").get("status").asText()).isEqualTo("UPLOADING");
    }

    @Test
    @Order(4)
    void badChunkRequestsAreRejectedWithoutSideEffects() {
        awaitHealthyNodes(3);
        byte[] data = randomBytes(1000, 400);
        String uploadId = createUpload("bad-chunk-0001", "bad.bin", data.length).getBody().get("uploadId").asText();

        ResponseEntity<JsonNode> wrongSha = putChunk(uploadId, 0, data, "0".repeat(64));
        assertThat(wrongSha.getStatusCode().value()).isEqualTo(422);
        assertThat(wrongSha.getBody().get("code").asText()).isEqualTo("CHECKSUM_MISMATCH");

        ResponseEntity<JsonNode> wrongLength = putChunk(uploadId, 0, java.util.Arrays.copyOf(data, 999), null);
        assertThat(wrongLength.getStatusCode().value()).isEqualTo(400);
        assertThat(wrongLength.getBody().get("code").asText()).isEqualTo("LENGTH_MISMATCH");

        assertThat(putChunk(uploadId, 1, data, null).getStatusCode().value()).isEqualTo(404);

        Integer replicas = jdbc.queryForObject("SELECT count(*) FROM chunk_replicas r JOIN chunks c ON c.id = r.chunk_id "
                + "JOIN upload_sessions s ON s.object_id = c.object_id WHERE s.id = ?::uuid", Integer.class, uploadId);
        assertThat(replicas).isZero();

        // Same index, different content after a successful write is a conflict.
        assertThat(putChunk(uploadId, 0, data, null).getStatusCode().value()).isEqualTo(200);
        byte[] other = randomBytes(1000, 401);
        ResponseEntity<JsonNode> conflict = putChunk(uploadId, 0, other, null);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().get("code").asText()).isEqualTo("CHUNK_CONFLICT");
    }

    @Test
    @Order(5)
    void corruptReplicaIsDetectedOnReadAndNeverServed() throws Exception {
        awaitHealthyNodes(3);
        byte[] data = randomBytes(2 * CHUNK, 500);
        String objectId = uploadAll(data, "corrupt-key-0001");
        JsonNode chunk0 = detail(objectId).get("chunks").get(0);
        String chunkId = chunk0.get("chunkId").asText();

        // Downloads prefer node-1 (healthy, lowest ID), so corrupt that copy: flip the first byte in place.
        var exec = NODES.get("node-1").execInContainer("sh", "-c",
                "printf 'Z' | dd of=" + chunkPath(chunkId) + " bs=1 seek=0 count=1 conv=notrunc 2>/dev/null && echo ok");
        assertThat(exec.getStdout()).contains("ok");
        assertThat(shaOnNode("node-1", chunkId)).isNotEqualTo(chunk0.get("sha256").asText());

        ResponseEntity<byte[]> download = http.getForEntity("/api/v1/objects/" + objectId + "/download", byte[].class);
        assertThat(download.getStatusCode().value()).isEqualTo(200);
        assertThat(Sha256.of(download.getBody())).as("served from a verified replica").isEqualTo(Sha256.of(data));

        JsonNode after = detail(objectId);
        assertThat(after.get("object").get("status").asText()).isEqualTo("DEGRADED");
        JsonNode c0 = after.get("chunks").get(0);
        assertThat(c0.get("status").asText()).isEqualTo("UNDER_REPLICATED");
        Map<String, String> states = new LinkedHashMap<>();
        c0.get("replicas").forEach(r -> states.put(r.get("nodeId").asText(), r.get("state").asText()));
        assertThat(states).containsEntry("node-1", "CORRUPT").containsEntry("node-2", "VERIFIED")
                .containsEntry("node-3", "VERIFIED");
        Integer events = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'ReplicaCorrupt' "
                + "AND payload->>'chunkId' = ?", Integer.class, chunkId);
        assertThat(events).isEqualTo(1);
    }

    @Test
    @Order(6)
    void deleteIsTombstonedThenRemovesBytesFromEveryNode() throws Exception {
        awaitHealthyNodes(3);
        byte[] data = randomBytes(CHUNK + 1, 600);
        String objectId = uploadAll(data, "delete-key-0001");
        List<String> chunkIds = new ArrayList<>();
        detail(objectId).get("chunks").forEach(c -> chunkIds.add(c.get("chunkId").asText()));
        assertThat(shaOnNode("node-2", chunkIds.get(0))).isNotNull();

        for (int i = 0; i < 2; i++) {
            ResponseEntity<JsonNode> res = http.exchange("/api/v1/objects/" + objectId, HttpMethod.DELETE, null, JsonNode.class);
            assertThat(res.getStatusCode().value()).isEqualTo(202);
        }
        assertThat(http.getForEntity("/api/v1/objects/" + objectId + "/download", JsonNode.class)
                .getStatusCode().value()).isEqualTo(409);

        await("object DELETED", Duration.ofSeconds(30), () -> "DELETED".equals(
                jdbc.queryForObject("SELECT status FROM objects WHERE id = ?::uuid", String.class, objectId)));
        for (String node : NODES.keySet()) {
            for (String chunkId : chunkIds) {
                assertThat(shaOnNode(node, chunkId)).as("%s on %s", chunkId, node).isNull();
            }
        }
    }

    @Test
    @Order(7)
    void insufficientHealthyNodesNeverYieldComplete() {
        awaitHealthyNodes(3);
        var docker = DockerClientFactory.instance().client();
        String node3 = NODES.get("node-3").getContainerId();
        docker.pauseContainerCmd(node3).exec();
        try {
            await("node-3 SUSPECT", Duration.ofSeconds(30), () -> "SUSPECT".equals(
                    jdbc.queryForObject("SELECT state FROM storage_nodes WHERE id = 'node-3'", String.class)));

            byte[] data = randomBytes(1000, 700);
            ResponseEntity<JsonNode> created = createUpload("degraded-key-0001", "degraded.bin", data.length);
            String uploadId = created.getBody().get("uploadId").asText();
            ResponseEntity<JsonNode> put = putChunk(uploadId, 0, data, null);
            assertThat(put.getStatusCode().value()).isEqualTo(503);
            assertThat(put.getBody().get("code").asText()).isEqualTo("INSUFFICIENT_HEALTHY_NODES");

            ResponseEntity<JsonNode> done = http.postForEntity("/api/v1/uploads/" + uploadId + "/complete", null, JsonNode.class);
            assertThat(done.getStatusCode().value()).isEqualTo(409);
            String status = jdbc.queryForObject("SELECT status FROM objects WHERE id = ?::uuid", String.class,
                    created.getBody().get("objectId").asText());
            assertThat(status).isNotEqualTo("COMPLETE");
            Integer transitions = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = 'node-3' "
                    + "AND details->>'to' = 'SUSPECT'", Integer.class);
            assertThat(transitions).isGreaterThanOrEqualTo(1);
        } finally {
            docker.unpauseContainerCmd(node3).exec();
        }
        awaitHealthyNodes(3);
        Integer recovered = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = 'node-3' "
                + "AND details->>'from' = 'SUSPECT' AND details->>'to' = 'HEALTHY'", Integer.class);
        assertThat(recovered).as("SUSPECT -> HEALTHY on resumed heartbeats").isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(8)
    void nodeInventoryReportsHeartbeatDataAndAdmissionPolicyHolds() {
        awaitHealthyNodes(3);
        JsonNode nodes = http.getForEntity("/api/v1/nodes", JsonNode.class).getBody();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        nodes.forEach(n -> byId.put(n.get("id").asText(), n));

        for (String id : GOOD_NODES) {
            JsonNode n = byId.get(id);
            assertThat(n.get("state").asText()).isEqualTo("HEALTHY");
            assertThat(n.get("capacityBytes").asLong()).isPositive();
            assertThat(n.get("heartbeatAgeSeconds").asLong()).isBetween(0L, 3L);
            assertThat(n.toString()).doesNotContain(SECRETS.get(id));
        }
        // Wrong secret: every heartbeat is rejected, so the node is never even registered.
        assertThat(byId).doesNotContainKey("node-4");
        // Valid heartbeats but the configured endpoint is unreachable: registered, never admitted.
        assertThat(byId.get("node-5").get("state").asText()).isEqualTo("JOINING");
        Integer admitted = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id IN ('node-4', 'node-5') "
                + "AND details->>'to' = 'HEALTHY'", Integer.class);
        assertThat(admitted).isZero();
    }

    @Test
    @Order(9)
    void forgedOrTamperedHeartbeatsAreRejected() throws Exception {
        String path = "/internal/v1/nodes/node-1/heartbeat";
        byte[] body = json.writeValueAsBytes(new dev.bytevault.protocol.Heartbeat("node-1", 1, "http://evil:9100",
                "UP", true, 1, 1, Instant.now()));

        // No signature at all.
        HttpHeaders unsigned = new HttpHeaders();
        unsigned.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, unsigned), JsonNode.class)
                .getStatusCode().value()).isEqualTo(401);

        // Signed with the wrong secret.
        assertThat(postSigned(path, body, new dev.bytevault.protocol.RequestSigner("w".repeat(48)), "node-1",
                Sha256.of(body))).isEqualTo(401);

        // Correctly signed hash, but the body was altered afterwards.
        var signer = new dev.bytevault.protocol.RequestSigner(SECRETS.get("node-1"));
        byte[] tampered = new String(body).replace("\"UP\"", "\"DOWN\"").getBytes();
        assertThat(postSigned(path, tampered, signer, "node-1", Sha256.of(body))).isEqualTo(401);

        // node-1's valid signature replayed against node-2's path.
        assertThat(postSigned("/internal/v1/nodes/node-2/heartbeat", body, signer, "node-1", Sha256.of(body)))
                .isEqualTo(401);

        // None of this disturbed node-1.
        assertThat(jdbc.queryForObject("SELECT state FROM storage_nodes WHERE id = 'node-1'", String.class))
                .isEqualTo("HEALTHY");
    }

    int postSigned(String path, byte[] body, dev.bytevault.protocol.RequestSigner signer, String nodeId, String sha) {
        var h = signer.sign("POST", path, nodeId, sha);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-BV-Node-Id", h.nodeId());
        headers.set("X-BV-Timestamp", h.timestamp());
        headers.set("X-BV-Content-SHA256", h.contentSha256());
        headers.set("X-BV-Signature", h.signature());
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class)
                .getStatusCode().value();
    }
}
