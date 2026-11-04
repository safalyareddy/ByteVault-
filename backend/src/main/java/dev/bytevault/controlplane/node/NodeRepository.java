package dev.bytevault.controlplane.node;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NodeRepository {

    public record NodeRow(String id, String endpoint, NodeState state, int protocolVersion, long capacityBytes,
                          long usedBytes, long usableBytes, OffsetDateTime lastHeartbeatAt, OffsetDateTime stateChangedAt,
                          long version) {}

    private static final String COLUMNS = "id, endpoint, state, protocol_version, capacity_bytes, used_bytes, "
            + "usable_bytes, last_heartbeat_at, state_changed_at, version";

    private final JdbcClient jdbc;

    public NodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Registers a configured node as JOINING; updates the endpoint if it changed. */
    public void registerIfAbsent(String id, String endpoint, int protocolVersion) {
        jdbc.sql("""
                INSERT INTO storage_nodes (id, endpoint, state, protocol_version)
                VALUES (:id, :endpoint, 'JOINING', :pv)
                ON CONFLICT (id) DO UPDATE SET endpoint = EXCLUDED.endpoint, updated_at = now()
                WHERE storage_nodes.endpoint <> EXCLUDED.endpoint
                """).param("id", id).param("endpoint", endpoint).param("pv", protocolVersion).update();
    }

    public List<NodeRow> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM storage_nodes ORDER BY id").query(NodeRow.class).list();
    }

    public Optional<NodeRow> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM storage_nodes WHERE id = :id").param("id", id)
                .query(NodeRow.class).optional();
    }

    public List<NodeRow> findByState(NodeState state) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM storage_nodes WHERE state = :s ORDER BY id")
                .param("s", state.name()).query(NodeRow.class).list();
    }

    public void recordHeartbeat(String id, int protocolVersion, long capacityBytes, long usableBytes) {
        jdbc.sql("""
                UPDATE storage_nodes
                   SET last_heartbeat_at = now(), protocol_version = :pv, capacity_bytes = :cap,
                       usable_bytes = :usable, used_bytes = GREATEST(:cap - :usable, 0), updated_at = now()
                 WHERE id = :id
                """).param("id", id).param("pv", protocolVersion).param("cap", capacityBytes)
                .param("usable", usableBytes).update();
    }

    /** Compare-and-set state change. Returns false if the node was not in {@code from}. */
    public boolean transition(String id, NodeState from, NodeState to) {
        return jdbc.sql("""
                UPDATE storage_nodes
                   SET state = :to, state_changed_at = now(), updated_at = now(), version = version + 1
                 WHERE id = :id AND state = :from
                """).param("id", id).param("from", from.name()).param("to", to.name()).update() == 1;
    }
}
