package dev.bytevault.controlplane.object;

import dev.bytevault.controlplane.object.ObjectDtos.ChunkView;
import dev.bytevault.controlplane.object.ObjectDtos.ObjectDetail;
import dev.bytevault.controlplane.object.ObjectDtos.ObjectSummary;
import dev.bytevault.controlplane.object.ObjectDtos.Page;
import dev.bytevault.controlplane.object.ObjectDtos.ReplicaView;
import dev.bytevault.controlplane.web.ApiException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read-only object metadata, always scoped to the requesting owner. */
@Service
public class ObjectQueryService {

    static final String SUMMARY_COLUMNS = "id, name, content_type, total_size AS size_bytes, status, "
            + "replication_factor, chunk_count, created_at, updated_at";

    private final JdbcClient jdbc;

    public ObjectQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Page<ObjectSummary> list(String owner, int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "page must be >= 0 and size between 1 and 200");
        }
        List<ObjectSummary> items = jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM objects "
                        + "WHERE owner_id = :o AND status <> 'DELETED' ORDER BY created_at DESC, id LIMIT :lim OFFSET :off")
                .param("o", owner).param("lim", size).param("off", (long) page * size)
                .query(ObjectSummary.class).list();
        long total = jdbc.sql("SELECT count(*) FROM objects WHERE owner_id = :o AND status <> 'DELETED'")
                .param("o", owner).query(Long.class).single();
        return new Page<>(items, page, size, total);
    }

    public Optional<ObjectSummary> find(String owner, UUID id) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM objects WHERE id = :id AND owner_id = :o")
                .param("id", id).param("o", owner).query(ObjectSummary.class).optional();
    }

    public ObjectSummary require(String owner, UUID id) {
        return find(owner, id).orElseThrow(() -> ApiException.notFound("object"));
    }

    private record ChunkRow(UUID id, int chunkIndex, long byteOffset, int byteSize, String sha256, String status) {}

    private record ReplicaRow(UUID chunkId, String nodeId, String state, String nodeState, OffsetDateTime storedAt,
                              OffsetDateTime lastVerifiedAt, String lastError) {}

    public ObjectDetail detail(String owner, UUID id) {
        ObjectSummary object = require(owner, id);
        List<ChunkRow> chunks = jdbc.sql("SELECT id, chunk_index, byte_offset, byte_size, sha256, status FROM chunks "
                        + "WHERE object_id = :o ORDER BY chunk_index")
                .param("o", id).query(ChunkRow.class).list();
        Map<UUID, List<ReplicaView>> replicas = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT r.chunk_id, r.node_id, r.state, n.state AS node_state, r.stored_at, r.last_verified_at,
                       r.last_error
                  FROM chunk_replicas r JOIN chunks c ON c.id = r.chunk_id JOIN storage_nodes n ON n.id = r.node_id
                 WHERE c.object_id = :o
                 ORDER BY r.node_id
                """).param("o", id).query(ReplicaRow.class).list()
                .forEach(r -> replicas.computeIfAbsent(r.chunkId(), k -> new java.util.ArrayList<>())
                        .add(new ReplicaView(r.nodeId(), r.state(), r.nodeState(), r.storedAt(), r.lastVerifiedAt(),
                                r.lastError())));
        List<ChunkView> views = chunks.stream().map(c -> {
            List<ReplicaView> rs = replicas.getOrDefault(c.id(), List.of());
            int verified = (int) rs.stream().filter(r -> r.state().equals("VERIFIED")).count();
            return new ChunkView(c.chunkIndex(), c.id(), c.byteOffset(), c.byteSize(), c.sha256(), c.status(),
                    verified, rs);
        }).toList();
        return new ObjectDetail(object, views);
    }
}
