package dev.bytevault.node.web;

import dev.bytevault.node.config.NodeProperties;
import dev.bytevault.node.storage.ChunkStore;
import dev.bytevault.protocol.NodeProtocol;
import dev.bytevault.protocol.Sha256;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Chunk endpoints of protocol v1. Authentication is enforced by {@link NodeAuthFilter}. */
@RestController
public class ChunkController {

    private final ChunkStore store;
    private final NodeProperties props;

    public ChunkController(ChunkStore store, NodeProperties props) {
        this.store = store;
        this.props = props;
    }

    public record ChunkReceipt(String chunkId, String sha256, long sizeBytes) {}

    @PutMapping(NodeProtocol.CHUNKS_PATH + "/{chunkId}")
    public ResponseEntity<?> put(@PathVariable String chunkId,
                                 @RequestHeader(NodeProtocol.HEADER_CONTENT_SHA256) String declaredSha,
                                 HttpServletRequest request) throws IOException {
        UUID id = NodeProtocol.parseChunkId(chunkId);
        long length = request.getContentLengthLong();
        if (length < 0) {
            return NodeErrors.entity(HttpStatus.LENGTH_REQUIRED.value(), "LENGTH_REQUIRED", "Content-Length required");
        }
        if (length == 0 || length > props.maxChunkBytes()) {
            return NodeErrors.entity(HttpStatus.PAYLOAD_TOO_LARGE.value(), "INVALID_LENGTH",
                    "chunk length must be between 1 and " + props.maxChunkBytes() + " bytes");
        }
        if (!Sha256.isValidHex(declaredSha)) {
            return NodeErrors.entity(400, "INVALID_CHECKSUM", "X-BV-Content-SHA256 must be 64 lowercase hex chars");
        }
        try (InputStream body = request.getInputStream()) {
            ChunkStore.PutResult result = store.put(id, body, length, declaredSha);
            HttpStatus status = result.outcome() == ChunkStore.PutOutcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status).body(new ChunkReceipt(id.toString(), result.sha256(), result.sizeBytes()));
        }
    }

    @GetMapping(NodeProtocol.CHUNKS_PATH + "/{chunkId}")
    public ResponseEntity<?> get(@PathVariable String chunkId) throws IOException {
        UUID id = NodeProtocol.parseChunkId(chunkId);
        var found = store.find(id);
        if (found.isEmpty()) {
            return NodeErrors.entity(404, "CHUNK_NOT_FOUND", "chunk not stored on this node");
        }
        ChunkStore.StoredChunk chunk = found.get();
        // Streamed from disk as stored; the caller re-hashes and rejects mismatches (protocol rule).
        Resource body = new FileSystemResource(chunk.file());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(chunk.sizeBytes())
                .header(NodeProtocol.HEADER_STORED_SHA256, chunk.sha256())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    public record VerifyResult(String chunkId, String storedSha256, String actualSha256, long sizeBytes,
                               boolean intact, java.time.Instant verifiedAt) {}

    /** Re-hashes the bytes on disk. The caller compares {@code actualSha256} with its metadata. */
    @org.springframework.web.bind.annotation.PostMapping(NodeProtocol.CHUNKS_PATH + "/{chunkId}/verify")
    public ResponseEntity<?> verify(@PathVariable String chunkId) throws IOException {
        UUID id = NodeProtocol.parseChunkId(chunkId);
        var result = store.verify(id);
        if (result.isEmpty()) {
            return NodeErrors.entity(404, "CHUNK_NOT_FOUND", "chunk not stored on this node");
        }
        var v = result.get();
        return ResponseEntity.ok(new VerifyResult(id.toString(), v.storedSha256(), v.actualSha256(), v.sizeBytes(),
                v.intact(), java.time.Instant.now()));
    }

    public record ChunkPage(java.util.List<String> chunkIds, String nextAfter) {}

    /** Paged inventory of stored chunk IDs (reconciliation after a metadata restore). */
    @GetMapping(NodeProtocol.CHUNKS_PATH)
    public ChunkPage list(@org.springframework.web.bind.annotation.RequestParam(required = false) String after,
                          @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1000") int limit)
            throws IOException {
        int bounded = Math.min(Math.max(limit, 1), 1000);
        var ids = store.list(after == null ? null : NodeProtocol.parseChunkId(after), bounded);
        var strings = ids.stream().map(UUID::toString).toList();
        return new ChunkPage(strings, strings.size() == bounded ? strings.get(strings.size() - 1) : null);
    }

    @DeleteMapping(NodeProtocol.CHUNKS_PATH + "/{chunkId}")
    public ResponseEntity<Void> delete(@PathVariable String chunkId) throws IOException {
        store.delete(NodeProtocol.parseChunkId(chunkId));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return NodeErrors.entity(400, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(ChunkStore.ChunkValidationException.class)
    ResponseEntity<?> invalid(ChunkStore.ChunkValidationException e) {
        return NodeErrors.entity(422, e.code(), e.getMessage());
    }

    @ExceptionHandler(ChunkStore.ChunkConflictException.class)
    ResponseEntity<?> conflict(ChunkStore.ChunkConflictException e) {
        return NodeErrors.entity(409, "CHUNK_CONFLICT", e.getMessage());
    }
}
