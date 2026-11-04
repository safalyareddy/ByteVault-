package dev.bytevault.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bytevault.controlplane.events.Topics;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 1 gate: the control plane boots against real PostgreSQL and Kafka, Flyway applies the
 * schema to an empty database, topics are declared, and /api/v1/health reports UP. Also checks the
 * schema-level invariants that later phases depend on.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainersConfig.class)
class FoundationIT {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaAdmin kafkaAdmin;

    @org.junit.jupiter.api.BeforeEach
    void login() {
        TestAuth.asAdmin(http);
    }

    @Test
    void healthEndpointReportsDatabaseAndKafkaUp() {
        var res = http.getForEntity("/api/v1/health", String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("\"status\":\"UP\"", "\"db\":\"UP\"", "\"kafka\":\"UP\"");
        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void unknownEndpointReturnsErrorEnvelope() {
        var res = http.getForEntity("/api/v1/does-not-exist", String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody()).contains("\"code\":\"NOT_FOUND\"", "\"correlationId\"");
    }

    @Test
    void flywayAppliedBaselineSchema() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void allTopicsDeclared() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Set<String> names = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            assertThat(names).containsAll(Topics.ALL);
        }
    }

    @Test
    void replicasOfAChunkMustBeOnDistinctNodes() {
        UUID chunkId = insertChunk();
        insertNode("it-node-a");
        jdbc.update("INSERT INTO chunk_replicas (chunk_id, node_id, state) VALUES (?, 'it-node-a', 'PENDING')", chunkId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO chunk_replicas (chunk_id, node_id, state) VALUES (?, 'it-node-a', 'PENDING')", chunkId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_chunk_replicas_chunk_node");
    }

    @Test
    void verifiedReplicaRequiresChecksum() {
        UUID chunkId = insertChunk();
        insertNode("it-node-b");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO chunk_replicas (chunk_id, node_id, state) VALUES (?, 'it-node-b', 'VERIFIED')", chunkId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_verified_has_checksum");
    }

    @Test
    void auditLogIsAppendOnly() {
        UUID id = jdbc.queryForObject(
                "INSERT INTO audit_events (actor, action, resource_type, resource_id) "
                        + "VALUES ('it', 'TEST', 'NODE', 'n1') RETURNING id", UUID.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET actor = 'x' WHERE id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE id = ?", id))
                .hasMessageContaining("append-only");
    }

    @Test
    void onlyOneActiveRepairPerChunk() {
        UUID chunkId = insertChunk();
        jdbc.update("INSERT INTO repair_jobs (chunk_id, reason, state) VALUES (?, 'MANUAL', 'PENDING')", chunkId);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO repair_jobs (chunk_id, reason, state) VALUES (?, 'MANUAL', 'PENDING')", chunkId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_repair_jobs_active_chunk");
    }

    private UUID insertChunk() {
        UUID objectId = jdbc.queryForObject(
                "INSERT INTO objects (owner_id, name, total_size, chunk_size, chunk_count, status, replication_factor) "
                        + "VALUES ('it', 'f.bin', 10, 4194304, 1, 'INITIATED', 3) RETURNING id", UUID.class);
        return jdbc.queryForObject(
                "INSERT INTO chunks (object_id, chunk_index, byte_offset, byte_size, status) "
                        + "VALUES (?, 0, 0, 10, 'PENDING') RETURNING id", UUID.class, objectId);
    }

    private void insertNode(String id) {
        jdbc.update("INSERT INTO storage_nodes (id, endpoint, state, protocol_version) "
                + "VALUES (?, 'http://x:9100', 'HEALTHY', 1) ON CONFLICT DO NOTHING", id);
    }
}
