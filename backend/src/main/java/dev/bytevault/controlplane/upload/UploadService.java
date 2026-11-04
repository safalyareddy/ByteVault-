package dev.bytevault.controlplane.upload;

import dev.bytevault.controlplane.config.BytevaultProperties;
import dev.bytevault.controlplane.config.ClusterProperties;
import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.node.NodeClient;
import dev.bytevault.controlplane.node.NodeRepository;
import dev.bytevault.controlplane.node.NodeState;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import dev.bytevault.controlplane.placement.RendezvousPlacement;
import dev.bytevault.controlplane.upload.UploadDtos.ChunkUploadResult;
import dev.bytevault.controlplane.upload.UploadDtos.CompleteResult;
import dev.bytevault.controlplane.upload.UploadDtos.CreateUploadRequest;
import dev.bytevault.controlplane.upload.UploadDtos.ReplicaWrite;
import dev.bytevault.controlplane.upload.UploadDtos.UploadView;
import dev.bytevault.controlplane.web.ApiException;
import dev.bytevault.protocol.Sha256;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Upload sessions, chunk writes and completion (docs/architecture.md, ADR-0005).
 *
 * <p>Transaction discipline: metadata changes happen in short transactions before and after node
 * transfers; no transaction is open while bytes move over the network.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private static final int BUFFER = 64 * 1024;
    /** Free space a node must keep beyond the chunk itself to be eligible for placement. */
    private static final long NODE_FREE_SPACE_RESERVE = 256L * 1024 * 1024;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;
    private final NodeRepository nodes;
    private final NodeClient nodeClient;
    private final TransferPermits permits;
    private final ExecutorService replicaExecutor;
    private final BytevaultProperties props;
    private final ClusterProperties cluster;
    private final MeterRegistry meters;
    private final Counter uploadBytes;

    public UploadService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TransactionTemplate tx, OutboxWriter outbox,
                         NodeRepository nodes, NodeClient nodeClient, TransferPermits permits,
                         ExecutorService replicaTransferExecutor, BytevaultProperties props,
                         ClusterProperties cluster, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.tx = tx;
        this.outbox = outbox;
        this.nodes = nodes;
        this.nodeClient = nodeClient;
        this.permits = permits;
        this.replicaExecutor = replicaTransferExecutor;
        this.props = props;
        this.cluster = cluster;
        this.meters = meters;
        this.uploadBytes = Counter.builder("bv_upload_bytes_total")
                .description("Bytes of chunks fully stored at the required replication factor")
                .register(meters);
    }

    // ---------------------------------------------------------------- create

    public UploadView create(String owner, String idempotencyKey, CreateUploadRequest req) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8-128 characters of [A-Za-z0-9._:-]");
        }
        if (req.name().chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "name must not contain control characters");
        }
        if (req.sizeBytes() > props.maxUploadBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "OBJECT_TOO_LARGE",
                    "object exceeds the maximum of " + props.maxUploadBytes() + " bytes");
        }
        String contentType = req.contentType() == null || req.contentType().isBlank() ? null : req.contentType().strip();
        String fingerprint = Sha256.of((owner + "\n" + req.name() + "\n" + req.sizeBytes() + "\n" + contentType)
                .getBytes(StandardCharsets.UTF_8));
        try {
            return tx.execute(s -> findSession(owner, idempotencyKey)
                    .map(existing -> replay(existing, fingerprint))
                    .orElseGet(() -> insertNew(owner, idempotencyKey, fingerprint, req, contentType)));
        } catch (DuplicateKeyException race) {
            // A concurrent request with the same key won; return its session.
            return tx.execute(s -> replay(findSession(owner, idempotencyKey).orElseThrow(), fingerprint));
        }
    }

    private record SessionKeyRow(UUID id, String requestFingerprint) {}

    private Optional<SessionKeyRow> findSession(String owner, String key) {
        return jdbc.sql("SELECT id, request_fingerprint FROM upload_sessions WHERE owner_id = :o AND idempotency_key = :k")
                .param("o", owner).param("k", key).query(SessionKeyRow.class).optional();
    }

    private UploadView replay(SessionKeyRow existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with different parameters");
        }
        return view(existing.id(), true);
    }

    private UploadView insertNew(String owner, String key, String fingerprint, CreateUploadRequest req,
                                 String contentType) {
        int chunkSize = props.chunkSizeBytes();
        long size = req.sizeBytes();
        int chunkCount = Math.toIntExact((size + chunkSize - 1) / chunkSize);
        int rf = props.replicationFactor();
        UUID objectId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(cluster.getUploadSessionTtl());

        jdbc.sql("""
                INSERT INTO objects (id, owner_id, name, content_type, total_size, chunk_size, chunk_count, status,
                                     replication_factor)
                VALUES (:id, :owner, :name, :ct, :size, :cs, :cc, 'INITIATED', :rf)
                """).param("id", objectId).param("owner", owner).param("name", req.name()).param("ct", contentType)
                .param("size", size).param("cs", chunkSize).param("cc", chunkCount).param("rf", rf).update();

        List<Object[]> rows = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            long offset = (long) i * chunkSize;
            int bytes = (int) Math.min(chunkSize, size - offset);
            rows.add(new Object[] {objectId, i, offset, bytes});
        }
        jdbcTemplate.batchUpdate("INSERT INTO chunks (object_id, chunk_index, byte_offset, byte_size, status) "
                + "VALUES (?, ?, ?, ?, 'PENDING')", rows);

        jdbc.sql("""
                INSERT INTO upload_sessions (id, object_id, owner_id, idempotency_key, request_fingerprint, status,
                                             chunk_size, expires_at)
                VALUES (:id, :oid, :owner, :key, :fp, 'ACTIVE', :cs, :exp)
                """).param("id", uploadId).param("oid", objectId).param("owner", owner).param("key", key)
                .param("fp", fingerprint).param("cs", chunkSize).param("exp", expiresAt).update();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("objectId", objectId);
        payload.put("uploadId", uploadId);
        payload.put("ownerId", owner);
        payload.put("totalSize", size);
        payload.put("chunkSize", chunkSize);
        payload.put("chunkCount", chunkCount);
        payload.put("replicationFactor", rf);
        outbox.append("OBJECT", objectId.toString(), 0, "UploadSessionCreated", Topics.OBJECT_EVENTS,
                objectId.toString(), payload);
        return view(uploadId, false);
    }

    // ---------------------------------------------------------------- status

    private record SessionCtx(UUID uploadId, UUID objectId, String ownerId, String status, OffsetDateTime expiresAt,
                              long totalSize, int chunkSize, int chunkCount, int replicationFactor,
                              String objectStatus) {}

    private Optional<SessionCtx> session(UUID uploadId) {
        return jdbc.sql("""
                SELECT s.id AS upload_id, s.object_id, s.owner_id, s.status, s.expires_at, o.total_size,
                       o.chunk_size, o.chunk_count, o.replication_factor, o.status AS object_status
                  FROM upload_sessions s JOIN objects o ON o.id = s.object_id
                 WHERE s.id = :id
                """).param("id", uploadId).query(SessionCtx.class).optional();
    }

    private SessionCtx ownedSession(String owner, UUID uploadId) {
        return session(uploadId).filter(s -> s.ownerId().equals(owner))
                .orElseThrow(() -> ApiException.notFound("upload"));
    }

    public UploadView get(String owner, UUID uploadId) {
        ownedSession(owner, uploadId);
        return view(uploadId, false);
    }

    private UploadView view(UUID uploadId, boolean replayed) {
        SessionCtx s = session(uploadId).orElseThrow();
        List<Integer> missing = missingChunks(s.objectId(), s.replicationFactor(), 1000);
        int stored = s.chunkCount() - countMissing(s.objectId(), s.replicationFactor());
        return new UploadView(s.uploadId(), s.objectId(), s.status(), s.objectStatus(), s.totalSize(), s.chunkSize(),
                s.chunkCount(), s.replicationFactor(), s.expiresAt(), stored, missing, replayed);
    }

    private static final String MISSING_SQL = """
            FROM chunks c
            WHERE c.object_id = :o
              AND (c.status <> 'STORED'
                   OR (SELECT count(*) FROM chunk_replicas r WHERE r.chunk_id = c.id AND r.state = 'VERIFIED') < :rf)
            """;

    private List<Integer> missingChunks(UUID objectId, int rf, int limit) {
        return jdbc.sql("SELECT c.chunk_index " + MISSING_SQL + " ORDER BY c.chunk_index LIMIT :lim")
                .param("o", objectId).param("rf", rf).param("lim", limit).query(Integer.class).list();
    }

    private int countMissing(UUID objectId, int rf) {
        return jdbc.sql("SELECT count(*) " + MISSING_SQL).param("o", objectId).param("rf", rf)
                .query(Integer.class).single();
    }

    // ---------------------------------------------------------------- chunk

    private record ChunkRow(UUID id, int byteSize, String sha256, String status) {}

    private record ReplicaRow(String nodeId, String state) {}

    public ChunkUploadResult putChunk(String owner, UUID uploadId, int index, long contentLength, String clientSha256,
                                      InputStream body) {
        SessionCtx s = ownedSession(owner, uploadId);
        requireWritable(s);
        if (index < 0 || index >= s.chunkCount()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHUNK_INDEX_OUT_OF_RANGE",
                    "chunk index must be between 0 and " + (s.chunkCount() - 1));
        }
        if (clientSha256 != null && !Sha256.isValidHex(clientSha256)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHECKSUM",
                    "X-Chunk-SHA256 must be 64 lowercase hex characters");
        }
        ChunkRow chunk = jdbc.sql("SELECT id, byte_size, sha256, status FROM chunks WHERE object_id = :o AND chunk_index = :i")
                .param("o", s.objectId()).param("i", index).query(ChunkRow.class).single();
        if (contentLength < 0) {
            throw new ApiException(HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED", "Content-Length is required");
        }
        if (contentLength != chunk.byteSize()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LENGTH_MISMATCH",
                    "chunk " + index + " must be exactly " + chunk.byteSize() + " bytes",
                    Map.of("expectedBytes", chunk.byteSize(), "contentLength", contentLength));
        }

        try (TransferPermits.Permit permit = permits.acquire()) {
            Path spool = permit.spoolFile();
            String sha = spool(body, contentLength, spool);
            if (clientSha256 != null && !clientSha256.equals(sha)) {
                failure("client_checksum_mismatch");
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECKSUM_MISMATCH",
                        "received bytes do not match X-Chunk-SHA256");
            }
            if (chunk.sha256() != null && !chunk.sha256().equals(sha)) {
                failure("chunk_conflict");
                throw new ApiException(HttpStatus.CONFLICT, "CHUNK_CONFLICT",
                        "chunk " + index + " was already uploaded with different content");
            }
            return writeReplicas(s, index, chunk, sha, spool);
        } catch (IOException e) {
            failure("io_error");
            throw new ApiException(HttpStatus.BAD_REQUEST, "UPLOAD_STREAM_FAILED", "could not read chunk body");
        }
    }

    private static void requireWritable(SessionCtx s) {
        switch (s.status()) {
            case "ACTIVE" -> {
                if (s.expiresAt().isBefore(OffsetDateTime.now())) {
                    throw new ApiException(HttpStatus.GONE, "UPLOAD_EXPIRED", "upload session has expired");
                }
            }
            case "COMPLETED" -> throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_ALREADY_COMPLETED",
                    "upload is already complete");
            default -> throw new ApiException(HttpStatus.GONE, "UPLOAD_CLOSED", "upload session is " + s.status());
        }
    }

    /** Streams the body to the spool file with a bounded buffer, enforcing the exact length. */
    private static String spool(InputStream body, long expected, Path file) throws IOException {
        MessageDigest digest = Sha256.newDigest();
        long total = 0;
        try (OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = body.read(buf)) != -1) {
                total += n;
                if (total > expected) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "LENGTH_MISMATCH", "body longer than Content-Length");
                }
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        if (total != expected) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LENGTH_MISMATCH",
                    "received " + total + " of " + expected + " bytes");
        }
        return Sha256.hex(digest.digest());
    }

    private ChunkUploadResult writeReplicas(SessionCtx s, int index, ChunkRow chunk, String sha, Path spool) {
        int rf = s.replicationFactor();
        List<ReplicaRow> existing = replicas(chunk.id());
        Set<String> verified = new HashSet<>();
        existing.stream().filter(r -> r.state().equals("VERIFIED")).forEach(r -> verified.add(r.nodeId()));

        if (chunk.status().equals("STORED") && verified.size() >= rf) {
            return result(index, sha, chunk.id(), rf); // idempotent retry: nothing to write
        }

        int needed = rf - verified.size();
        List<String> eligible = nodes.findByState(NodeState.HEALTHY).stream()
                .filter(n -> !verified.contains(n.id()))
                .filter(n -> n.usableBytes() >= chunk.byteSize() + NODE_FREE_SPACE_RESERVE)
                .map(NodeRepository.NodeRow::id)
                .toList();
        List<String> ranked = RendezvousPlacement.rank(RendezvousPlacement.chunkKey(s.objectId(), index), eligible);
        if (ranked.size() < needed) {
            failure("insufficient_nodes");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INSUFFICIENT_HEALTHY_NODES",
                    "not enough healthy nodes with capacity to place " + needed + " more replica(s)",
                    Map.of("replicationFactor", rf, "verifiedReplicas", verified.size(),
                            "eligibleNodes", ranked.size()));
        }
        List<String> targets = ranked.subList(0, needed);

        tx.executeWithoutResult(t -> {
            int updated = jdbc.sql("""
                    UPDATE chunks SET sha256 = :sha, updated_at = now(), version = version + 1
                     WHERE id = :id AND (sha256 IS NULL OR sha256 = :sha)
                    """).param("sha", sha).param("id", chunk.id()).update();
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "CHUNK_CONFLICT",
                        "chunk " + index + " was concurrently uploaded with different content");
            }
            jdbc.sql("""
                    UPDATE objects SET status = 'UPLOADING', updated_at = now(), version = version + 1
                     WHERE id = :id AND status = 'INITIATED'
                    """).param("id", s.objectId()).update();
            for (String node : targets) {
                jdbc.sql("""
                        INSERT INTO chunk_replicas (chunk_id, node_id, state) VALUES (:c, :n, 'PENDING')
                        ON CONFLICT (chunk_id, node_id) DO UPDATE
                           SET state = 'PENDING', last_error = NULL, updated_at = now(),
                               version = chunk_replicas.version + 1
                         WHERE chunk_replicas.state IN ('PENDING', 'FAILED_WRITE')
                        """).param("c", chunk.id()).param("n", node).update();
            }
        });

        Map<String, Future<NodeClient.PutResult>> inFlight = new LinkedHashMap<>();
        for (String node : targets) {
            inFlight.put(node, replicaExecutor.submit(() -> nodeClient.putChunk(node, chunk.id(), spool, sha)));
        }
        Map<String, NodeClient.PutResult> results = new LinkedHashMap<>();
        inFlight.forEach((node, f) -> results.put(node, await(f)));

        boolean newlyStored = Boolean.TRUE.equals(tx.execute(t -> {
            results.forEach((node, r) -> {
                if (r.success()) {
                    jdbc.sql("""
                            UPDATE chunk_replicas
                               SET state = 'VERIFIED', stored_checksum = :sha, stored_at = now(),
                                   last_verified_at = now(), last_error = NULL, updated_at = now(),
                                   version = version + 1
                             WHERE chunk_id = :c AND node_id = :n AND state = 'PENDING'
                            """).param("sha", sha).param("c", chunk.id()).param("n", node).update();
                } else {
                    jdbc.sql("""
                            UPDATE chunk_replicas
                               SET state = 'FAILED_WRITE', last_error = :err, updated_at = now(), version = version + 1
                             WHERE chunk_id = :c AND node_id = :n AND state = 'PENDING'
                            """).param("err", r.error()).param("c", chunk.id()).param("n", node).update();
                }
            });
            int verifiedNow = verifiedCount(chunk.id());
            if (verifiedNow < rf) {
                return false;
            }
            int moved = jdbc.sql("UPDATE chunks SET status = 'STORED', updated_at = now(), version = version + 1 "
                    + "WHERE id = :id AND status = 'PENDING'").param("id", chunk.id()).update();
            if (moved == 1) {
                List<String> holders = jdbc.sql("SELECT node_id FROM chunk_replicas WHERE chunk_id = :c AND state = 'VERIFIED' ORDER BY node_id")
                        .param("c", chunk.id()).query(String.class).list();
                outbox.append("CHUNK", chunk.id().toString(), 1, "ChunkStored", Topics.CHUNK_EVENTS,
                        s.objectId() + ":" + index, Map.of("objectId", s.objectId(), "chunkIndex", index,
                                "chunkId", chunk.id(), "sha256", sha, "nodeIds", holders));
            }
            return moved == 1;
        }));
        if (newlyStored) {
            uploadBytes.increment(chunk.byteSize());
        }
        results.forEach((node, r) -> {
            if (!r.success()) {
                failure("replica_write_failed");
                log.warn("Replica write failed: object={} chunk={} node={} error={}", s.objectId(), index, node, r.error());
            }
        });
        return result(index, sha, chunk.id(), rf);
    }

    private NodeClient.PutResult await(Future<NodeClient.PutResult> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NodeClient.PutResult(false, null, "interrupted");
        } catch (ExecutionException e) {
            return new NodeClient.PutResult(false, null, String.valueOf(e.getCause()));
        }
    }

    private List<ReplicaRow> replicas(UUID chunkId) {
        return jdbc.sql("SELECT node_id, state FROM chunk_replicas WHERE chunk_id = :c ORDER BY node_id")
                .param("c", chunkId).query(ReplicaRow.class).list();
    }

    private int verifiedCount(UUID chunkId) {
        return jdbc.sql("SELECT count(*) FROM chunk_replicas WHERE chunk_id = :c AND state = 'VERIFIED'")
                .param("c", chunkId).query(Integer.class).single();
    }

    private record ReplicaDetailRow(String nodeId, String state, String lastError) {}

    private ChunkUploadResult result(int index, String sha, UUID chunkId, int rf) {
        List<ReplicaWrite> replicas = jdbc.sql(
                        "SELECT node_id, state, last_error FROM chunk_replicas WHERE chunk_id = :c ORDER BY node_id")
                .param("c", chunkId).query(ReplicaDetailRow.class).list().stream()
                .map(r -> new ReplicaWrite(r.nodeId(), r.state(), r.lastError()))
                .toList();
        int verified = (int) replicas.stream().filter(r -> r.state().equals("VERIFIED")).count();
        return new ChunkUploadResult(index, sha, verified >= rf, verified, rf, replicas);
    }

    private void failure(String reason) {
        meters.counter("bv_upload_failures_total", "reason", reason).increment();
    }

    // ---------------------------------------------------------------- complete

    public CompleteResult complete(String owner, UUID uploadId) {
        return tx.execute(t -> {
            SessionCtx s = jdbc.sql("""
                    SELECT s.id AS upload_id, s.object_id, s.owner_id, s.status, s.expires_at, o.total_size,
                           o.chunk_size, o.chunk_count, o.replication_factor, o.status AS object_status
                      FROM upload_sessions s JOIN objects o ON o.id = s.object_id
                     WHERE s.id = :id
                       FOR UPDATE OF s, o
                    """).param("id", uploadId).query(SessionCtx.class).optional()
                    .filter(x -> x.ownerId().equals(owner))
                    .orElseThrow(() -> ApiException.notFound("upload"));
            if (s.status().equals("COMPLETED")) {
                return new CompleteResult(s.objectId(), s.objectStatus(), s.totalSize(), s.chunkCount(),
                        s.replicationFactor());
            }
            requireWritable(s);
            List<Integer> missing = missingChunks(s.objectId(), s.replicationFactor(), 100);
            if (!missing.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "INCOMPLETE_UPLOAD",
                        "not every chunk has " + s.replicationFactor() + " verified replicas",
                        Map.of("missingChunkIndexes", missing, "replicationFactor", s.replicationFactor()));
            }
            Long version = jdbc.sql("""
                    UPDATE objects SET status = 'COMPLETE', updated_at = now(), version = version + 1
                     WHERE id = :id AND status IN ('INITIATED', 'UPLOADING')
                    RETURNING version
                    """).param("id", s.objectId()).query(Long.class).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                            "object is " + s.objectStatus()));
            jdbc.sql("UPDATE upload_sessions SET status = 'COMPLETED', updated_at = now(), version = version + 1 "
                    + "WHERE id = :id").param("id", uploadId).update();
            outbox.append("OBJECT", s.objectId().toString(), version, "ObjectCompleted", Topics.OBJECT_EVENTS,
                    s.objectId().toString(), Map.of("objectId", s.objectId(), "totalSize", s.totalSize(),
                            "chunkCount", s.chunkCount(), "replicationFactor", s.replicationFactor()));
            return new CompleteResult(s.objectId(), "COMPLETE", s.totalSize(), s.chunkCount(), s.replicationFactor());
        });
    }
}
