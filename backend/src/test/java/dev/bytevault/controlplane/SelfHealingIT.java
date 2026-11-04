package dev.bytevault.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.bytevault.protocol.Sha256;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * Phase 5 gate: self-healing against four real node containers with RF=3, so a lost or corrupt
 * replica has somewhere to go. Faults are real: containers are paused (heartbeats stop) and chunk
 * files are modified on a node's own disk. Every recovery is checked on node disks, not only in
 * metadata, and by downloading and hashing the object.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.datasource.password=test",
        "bytevault.chunk-size-bytes=1048576",
        "bytevault.heartbeat-interval=1s",
        "bytevault.suspect-after=2s",
        "bytevault.offline-after=4s",
        "bytevault.failure-detector-interval=500ms",
        "bytevault.deletion-interval=1s",
        "bytevault.cluster.node-request-timeout=5s",
        "bytevault.cluster.admission-heartbeats=2",
        "bytevault.repair.planner-interval=1s",
        "bytevault.repair.worker-interval=500ms",
        "bytevault.repair.reconcile-interval=1s",
        "bytevault.repair.capacity-wait=2s",
        "bytevault.repair.scrub.enabled=false",
        "bytevault.repair.scrub.pause-between-chunks=0ms",
        "bytevault.admin-token=" + SelfHealingIT.ADMIN_TOKEN})
@Import(ContainersConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SelfHealingIT {

    static final String ADMIN_TOKEN = "self-healing-admin-token-0123456789abcd";
    static final int CHUNK = 1024 * 1024;
    static final String DATA_DIR = "/var/lib/bytevault/node";
    static final List<String> IDS = List.of("node-1", "node-2", "node-3", "node-4");
    static final Map<String, GenericContainer<?>> NODES = new LinkedHashMap<>();
    static final int CONTROL_PLANE_PORT = StorageIT.freePort();

    static String secret(String id) {
        return id.replace("node-", "s").repeat(24);
    }

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            org.testcontainers.Testcontainers.exposeHostPorts(CONTROL_PLANE_PORT);
            ImageFromDockerfile image = new ImageFromDockerfile("bytevault-storage-node-it", false)
                    .withFileFromPath("Dockerfile", Path.of("../storage-node/Dockerfile"))
                    .withFileFromPath("target/bytevault-storage-node.jar",
                            Path.of("../storage-node/target/bytevault-storage-node.jar"));
            for (String id : IDS) {
                GenericContainer<?> c = new GenericContainer<>(image)
                        .withEnv("BV_NODE_ID", id)
                        .withEnv("BV_NODE_AUTH_SECRET", secret(id))
                        .withEnv("BV_CONTROL_PLANE_URL", "http://host.testcontainers.internal:" + CONTROL_PLANE_PORT)
                        .withEnv("BV_HEARTBEAT_INTERVAL", "1s")
                        .withExposedPorts(9100)
                        .waitingFor(Wait.forHttp("/internal/v1/health").forStatusCode(200)
                                .withStartupTimeout(Duration.ofSeconds(90)));
                c.start();
                NODES.put(id, c);
            }
        }
    }

    @DynamicPropertySource
    static void cluster(DynamicPropertyRegistry r) {
        List<String> nodes = new ArrayList<>();
        List<String> secrets = new ArrayList<>();
        NODES.forEach((id, c) -> {
            nodes.add(id + "=http://" + c.getHost() + ":" + c.getMappedPort(9100));
            secrets.add(id + ":" + secret(id));
        });
        r.add("server.port", () -> CONTROL_PLANE_PORT);
        r.add("bytevault.cluster.nodes", () -> String.join(",", nodes));
        r.add("bytevault.cluster.node-secrets", () -> String.join(",", secrets));
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    // ------------------------------------------------------------------ helpers

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

    String nodeState(String id) {
        return jdbc.queryForObject("SELECT state FROM storage_nodes WHERE id = ?", String.class, id);
    }

    void awaitAllHealthy() {
        await("4 nodes HEALTHY", Duration.ofSeconds(60), () -> jdbc.queryForObject(
                "SELECT count(*) FROM storage_nodes WHERE state = 'HEALTHY'", Integer.class) == 4);
    }

    void awaitNodeState(String id, String state) {
        await(id + " " + state, Duration.ofSeconds(60), () -> state.equals(nodeState(id)));
    }

    String objectStatus(String objectId) {
        return jdbc.queryForObject("SELECT status FROM objects WHERE id = ?::uuid", String.class, objectId);
    }

    /** Uploads via the public API; returns the object ID. */
    String upload(byte[] data, String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", key);
        h.setContentType(MediaType.APPLICATION_JSON);
        JsonNode created = http.exchange("/api/v1/uploads", HttpMethod.POST, new HttpEntity<>(
                Map.of("name", key + ".bin", "sizeBytes", data.length), h), JsonNode.class).getBody();
        String uploadId = created.get("uploadId").asText();
        for (int i = 0; i < created.get("chunkCount").asInt(); i++) {
            byte[] part = java.util.Arrays.copyOfRange(data, i * CHUNK, Math.min(data.length, (i + 1) * CHUNK));
            HttpHeaders ch = new HttpHeaders();
            ch.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            assertThat(http.exchange("/api/v1/uploads/" + uploadId + "/chunks/" + i, HttpMethod.PUT,
                    new HttpEntity<>(part, ch), JsonNode.class).getStatusCode().value()).isEqualTo(200);
        }
        JsonNode done = http.postForEntity("/api/v1/uploads/" + uploadId + "/complete", null, JsonNode.class).getBody();
        assertThat(done.get("status").asText()).isEqualTo("COMPLETE");
        return done.get("objectId").asText();
    }

    static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** chunkId -> list of node IDs holding a VERIFIED replica. */
    Map<String, List<String>> verifiedHolders(String objectId) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT c.id::text AS chunk_id, r.node_id FROM chunks c
                  JOIN chunk_replicas r ON r.chunk_id = c.id AND r.state = 'VERIFIED'
                 WHERE c.object_id = ?::uuid ORDER BY c.chunk_index, r.node_id
                """, objectId)) {
            out.computeIfAbsent((String) row.get("chunk_id"), k -> new ArrayList<>()).add((String) row.get("node_id"));
        }
        return out;
    }

    String chunkSha(String chunkId) {
        return jdbc.queryForObject("SELECT sha256 FROM chunks WHERE id = ?::uuid", String.class, chunkId);
    }

    static String chunkPath(String chunkId) {
        return DATA_DIR + "/chunks/" + chunkId.substring(0, 2) + "/" + chunkId.substring(2, 4) + "/" + chunkId + ".chunk";
    }

    static String shaOnNode(String nodeId, String chunkId) throws Exception {
        var r = NODES.get(nodeId).execInContainer("sh", "-c", "sha256sum " + chunkPath(chunkId) + " 2>/dev/null");
        return r.getExitCode() == 0 ? r.getStdout().split("\\s+")[0] : null;
    }

    /** Every chunk has exactly RF=3 VERIFIED replicas, each with correct bytes on that node's disk. */
    void assertFullyReplicatedOnDisk(String objectId) throws Exception {
        Map<String, List<String>> holders = verifiedHolders(objectId);
        for (var e : holders.entrySet()) {
            assertThat(e.getValue()).as("chunk %s holders", e.getKey()).hasSize(3).doesNotHaveDuplicates();
            for (String node : e.getValue()) {
                assertThat(shaOnNode(node, e.getKey())).as("%s on %s", e.getKey(), node).isEqualTo(chunkSha(e.getKey()));
            }
        }
    }

    void assertDownloadMatches(String objectId, byte[] data) {
        ResponseEntity<byte[]> res = http.getForEntity("/api/v1/objects/" + objectId + "/download", byte[].class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(Sha256.of(res.getBody())).isEqualTo(Sha256.of(data));
    }

    void pause(String id) {
        DockerClientFactory.instance().client().pauseContainerCmd(NODES.get(id).getContainerId()).exec();
    }

    void unpause(String id) {
        DockerClientFactory.instance().client().unpauseContainerCmd(NODES.get(id).getContainerId()).exec();
    }

    HttpHeaders admin(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.set("X-BV-Admin-Token", token);
        }
        return h;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void nodeLossIsDetectedRepairedAndReconciledOnRejoin() throws Exception {
        awaitAllHealthy();
        byte[] data = random(2 * CHUNK + 5_000, 1);
        String objectId = upload(data, "heal-node-loss-0001");
        Map<String, List<String>> before = verifiedHolders(objectId);
        String victim = before.values().iterator().next().get(0);
        List<String> victimChunks = before.entrySet().stream().filter(e -> e.getValue().contains(victim))
                .map(Map.Entry::getKey).toList();

        pause(victim);
        try {
            awaitNodeState(victim, "OFFLINE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chunk_replicas WHERE node_id = ? AND state = 'LOST' "
                    + "AND chunk_id IN (SELECT id FROM chunks WHERE object_id = ?::uuid)", Integer.class, victim, objectId))
                    .isEqualTo(victimChunks.size());

            await("object restored to COMPLETE", Duration.ofSeconds(60), () -> "COMPLETE".equals(objectStatus(objectId))
                    && verifiedHolders(objectId).values().stream().allMatch(h -> h.size() == 3 && !h.contains(victim)));
            assertFullyReplicatedOnDisk(objectId);
            assertDownloadMatches(objectId, data);

            for (String chunkId : victimChunks) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_jobs WHERE chunk_id = ?::uuid "
                        + "AND state = 'COMMITTED' AND reason = 'NODE_OFFLINE' AND target_node_id <> ?",
                        Integer.class, chunkId, victim)).isEqualTo(1);
            }
            List<String> objectEvents = jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? "
                    + "ORDER BY created_at", String.class, objectId);
            assertThat(objectEvents).containsSubsequence("ObjectCompleted", "ObjectDegraded", "ObjectRestored");
        } finally {
            unpause(victim);
        }

        // Rejoin: re-admitted, its now-redundant LOST copies are deleted, never 4 VERIFIED.
        awaitNodeState(victim, "HEALTHY");
        await("victim's stale copies removed", Duration.ofSeconds(60), () -> victimChunks.stream().allMatch(c -> {
            try {
                return shaOnNode(victim, c) == null;
            } catch (Exception e) {
                return false;
            }
        }));
        assertFullyReplicatedOnDisk(objectId);
        assertThat(jdbc.queryForList("SELECT DISTINCT details->>'to' FROM audit_events WHERE resource_id = ?",
                String.class, victim)).contains("SUSPECT", "OFFLINE", "JOINING", "HEALTHY");
    }

    @Test
    @Order(2)
    void corruptionFoundByScrubIsRepairedFromAVerifiedReplica() throws Exception {
        awaitAllHealthy();
        byte[] data = random(CHUNK, 2);
        String objectId = upload(data, "heal-corruption-0001");
        String chunkId = verifiedHolders(objectId).keySet().iterator().next();
        String victim = verifiedHolders(objectId).get(chunkId).get(0);

        var exec = NODES.get(victim).execInContainer("sh", "-c",
                "printf 'Z' | dd of=" + chunkPath(chunkId) + " bs=1 seek=1000 count=1 conv=notrunc 2>/dev/null && echo ok");
        assertThat(exec.getStdout()).contains("ok");
        assertThat(shaOnNode(victim, chunkId)).isNotEqualTo(chunkSha(chunkId));

        assertThat(http.exchange("/api/v1/admin/scrub", HttpMethod.POST, new HttpEntity<>(admin(null)), JsonNode.class)
                .getStatusCode().value()).isEqualTo(401);
        JsonNode summary = http.exchange("/api/v1/admin/scrub", HttpMethod.POST, new HttpEntity<>(admin(ADMIN_TOKEN)),
                JsonNode.class).getBody();
        assertThat(summary.get("corrupt").asInt()).isGreaterThanOrEqualTo(1);

        await("corrupt replica repaired", Duration.ofSeconds(60), () -> "COMPLETE".equals(objectStatus(objectId))
                && jdbc.queryForObject("SELECT count(*) FROM repair_jobs WHERE chunk_id = ?::uuid AND reason = 'CORRUPTION' "
                        + "AND state = 'COMMITTED'", Integer.class, chunkId) == 1
                && verifiedHolders(objectId).get(chunkId).size() == 3);
        await("no corrupt bytes left anywhere", Duration.ofSeconds(30), () -> {
            try {
                String onVictim = shaOnNode(victim, chunkId);
                return onVictim == null || onVictim.equals(chunkSha(chunkId));
            } catch (Exception e) {
                return false;
            }
        });
        assertFullyReplicatedOnDisk(objectId);
        assertDownloadMatches(objectId, data);
    }

    @Test
    @Order(3)
    void drainMovesReplicasBeforeTheNodeIsSafeToRemove() throws Exception {
        awaitAllHealthy();
        byte[] data = random(2 * CHUNK, 3);
        String objectId = upload(data, "heal-drain-0001");
        String drained = verifiedHolders(objectId).values().iterator().next().get(0);
        List<String> drainedChunks = verifiedHolders(objectId).entrySet().stream()
                .filter(e -> e.getValue().contains(drained)).map(Map.Entry::getKey).toList();
        String path = "/api/v1/nodes/" + drained + "/drain";

        assertThat(http.exchange(path, HttpMethod.POST, new HttpEntity<>(Map.of("reason", "x"), admin(null)),
                JsonNode.class).getStatusCode().value()).isEqualTo(401);
        JsonNode started = http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "hardware swap"), admin(ADMIN_TOKEN)), JsonNode.class).getBody();
        assertThat(started.get("state").asText()).isEqualTo("DRAINING");
        assertThat(started.get("safeToRemove").asBoolean()).isFalse();

        // New uploads avoid the draining node.
        String other = upload(random(1000, 33), "heal-drain-new-0001");
        assertThat(verifiedHolders(other).values()).allSatisfy(h -> assertThat(h).doesNotContain(drained));

        await("drain complete", Duration.ofSeconds(90), () -> http.getForEntity(path, JsonNode.class).getBody()
                .get("safeToRemove").asBoolean());
        assertThat(verifiedHolders(objectId).values()).allSatisfy(h -> assertThat(h).hasSize(3).doesNotContain(drained));
        for (String c : drainedChunks) {
            assertThat(shaOnNode(drained, c)).as("bytes removed from drained node").isNull();
        }
        assertThat(objectStatus(objectId)).as("drain never degrades the object").isEqualTo("COMPLETE");
        assertFullyReplicatedOnDisk(objectId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE resource_id = ? AND actor = 'admin-token' "
                + "AND details->>'to' = 'DRAINING'", Integer.class, drained)).isEqualTo(1);

        JsonNode cancelled = http.exchange(path + "/cancel", HttpMethod.POST, new HttpEntity<>(admin(ADMIN_TOKEN)),
                JsonNode.class).getBody();
        assertThat(cancelled.get("state").asText()).isEqualTo("HEALTHY");
    }

    @Test
    @Order(4)
    void withTooFewHealthyNodesRepairWaitsAndNeverLowersReplication() throws Exception {
        awaitAllHealthy();
        byte[] data = random(CHUNK, 4);
        String objectId = upload(data, "heal-capacity-0001");
        String chunkId = verifiedHolders(objectId).keySet().iterator().next();
        List<String> holders = verifiedHolders(objectId).get(chunkId);
        String a = holders.get(0);
        String b = holders.get(1);

        pause(a);
        pause(b);
        try {
            awaitNodeState(a, "OFFLINE");
            awaitNodeState(b, "OFFLINE");
            // One survivor plus the one spare healthy node: repair can reach 2 copies, never 3.
            await("repair reaches the spare node", Duration.ofSeconds(60),
                    () -> verifiedHolders(objectId).get(chunkId).size() == 2);
            await("job waiting for capacity", Duration.ofSeconds(30), () -> jdbc.queryForObject(
                    "SELECT count(*) FROM repair_jobs WHERE chunk_id = ?::uuid AND state = 'PENDING' "
                            + "AND last_error LIKE 'waiting for capacity%'", Integer.class, chunkId) == 1);
            Thread.sleep(3_000);
            assertThat(objectStatus(objectId)).isEqualTo("DEGRADED");
            assertThat(jdbc.queryForObject("SELECT replication_factor FROM objects WHERE id = ?::uuid", Integer.class,
                    objectId)).as("RF never lowered").isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_jobs WHERE chunk_id = ?::uuid AND state = 'ABANDONED'",
                    Integer.class, chunkId)).as("capacity waits do not burn attempts").isZero();
            assertDownloadMatches(objectId, data); // still readable while degraded
        } finally {
            unpause(a);
            unpause(b);
        }
        awaitAllHealthy();
        await("restored after nodes return", Duration.ofSeconds(60), () -> "COMPLETE".equals(objectStatus(objectId))
                && verifiedHolders(objectId).get(chunkId).size() == 3);
        assertFullyReplicatedOnDisk(objectId);
        assertDownloadMatches(objectId, data);
    }

    @Test
    @Order(5)
    void repairApiAndMetricsReflectRealWork() {
        JsonNode repairs = http.getForEntity("/api/v1/repairs?state=COMMITTED&limit=200", JsonNode.class).getBody();
        assertThat(repairs.size()).isGreaterThanOrEqualTo(4);
        for (JsonNode j : repairs) {
            if (j.get("targetNodeId").isNull()) {
                continue; // no-op commit ("already satisfied")
            }
            assertThat(j.get("sourceNodeId").asText()).isNotEqualTo(j.get("targetNodeId").asText());
        }
        // (The Prometheus endpoint is disabled in Spring Boot tests; read the registry directly.)
        assertThat(meters.find("bv_repair_jobs_total").tag("result", "committed").counter().count())
                .isGreaterThanOrEqualTo(4);
        assertThat(meters.find("bv_checksum_failures_total").tag("path", "scrub").counter().count())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.find("bv_under_replicated_chunks").gauge().value()).isZero();
        assertThat(meters.find("bv_repair_duration_seconds").timer().count()).isGreaterThanOrEqualTo(4);
    }
}
