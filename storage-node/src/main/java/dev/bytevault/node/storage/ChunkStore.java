package dev.bytevault.node.storage;

import dev.bytevault.protocol.Sha256;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Durable chunk storage on the node's own volume.
 *
 * <p>Layout: {@code chunks/<aa>/<bb>/<uuid>.chunk} with a {@code <uuid>.sha256} sidecar holding the
 * checksum computed at write time. Paths derive only from a validated UUID. Write sequence: stream
 * to {@code tmp/} while hashing, verify length and checksum, fsync, write the sidecar, atomically
 * rename the chunk into place, fsync the directory. A chunk file therefore never exists without
 * its sidecar, and a crash mid-write leaves only a temp file.
 */
@Component
public class ChunkStore {

    private static final Logger log = LoggerFactory.getLogger(ChunkStore.class);
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int LOCK_STRIPES = 256;

    private final DataDirectory dataDirectory;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
    private final Timer writeTimer;

    public ChunkStore(DataDirectory dataDirectory, MeterRegistry meters) {
        this.dataDirectory = dataDirectory;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        this.writeTimer = Timer.builder("bv_chunk_write_seconds")
                .description("Durable chunk write duration on this node")
                .publishPercentiles(0.5, 0.95)
                .register(meters);
    }

    public enum PutOutcome { CREATED, ALREADY_PRESENT }

    public record PutResult(PutOutcome outcome, String sha256, long sizeBytes) {}

    public record StoredChunk(Path file, String sha256, long sizeBytes) {}

    /**
     * Stores a chunk. Idempotent for identical content; rejects different content for an existing
     * chunk ID with {@link ChunkConflictException}.
     */
    public PutResult put(UUID chunkId, InputStream body, long declaredLength, String expectedSha256) throws IOException {
        Timer.Sample sample = Timer.start();
        Path tmp = Files.createTempFile(dataDirectory.tmpDir(), chunkId + ".", ".part");
        try {
            MessageDigest digest = Sha256.newDigest();
            long written = 0;
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE);
                 OutputStream out = java.nio.channels.Channels.newOutputStream(ch)) {
                byte[] buf = new byte[BUFFER_BYTES];
                int n;
                while ((n = body.read(buf)) != -1) {
                    written += n;
                    if (written > declaredLength) {
                        throw new ChunkValidationException("LENGTH_MISMATCH", "body longer than declared length");
                    }
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                out.flush();
                ch.force(true);
            }
            if (written != declaredLength) {
                throw new ChunkValidationException("LENGTH_MISMATCH",
                        "received " + written + " bytes, declared " + declaredLength);
            }
            String actual = Sha256.hex(digest.digest());
            if (!actual.equals(expectedSha256)) {
                throw new ChunkValidationException("CHECKSUM_MISMATCH", "body checksum does not match declared checksum");
            }

            ReentrantLock lock = lockFor(chunkId);
            lock.lock();
            try {
                Optional<StoredChunk> existing = find(chunkId);
                if (existing.isPresent()) {
                    if (existing.get().sha256().equals(actual)) {
                        return new PutResult(PutOutcome.ALREADY_PRESENT, actual, existing.get().sizeBytes());
                    }
                    throw new ChunkConflictException(chunkId);
                }
                Path target = chunkFile(chunkId);
                Files.createDirectories(target.getParent());
                writeSidecar(chunkId, actual);
                moveAtomically(tmp, target);
                fsyncDirectory(target.getParent());
                return new PutResult(PutOutcome.CREATED, actual, written);
            } finally {
                lock.unlock();
            }
        } finally {
            Files.deleteIfExists(tmp);
            sample.stop(writeTimer);
        }
    }

    /** The stored chunk and the checksum recorded at write time, if present. */
    public Optional<StoredChunk> find(UUID chunkId) throws IOException {
        Path file = chunkFile(chunkId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        String sha = Files.readString(sidecarFile(chunkId), StandardCharsets.US_ASCII).strip();
        if (!Sha256.isValidHex(sha)) {
            throw new IOException("corrupt sidecar for chunk " + chunkId);
        }
        return Optional.of(new StoredChunk(file, sha, Files.size(file)));
    }

    /** Deletes the chunk and its sidecar. Returns false if it was already absent. Idempotent. */
    public boolean delete(UUID chunkId) throws IOException {
        ReentrantLock lock = lockFor(chunkId);
        lock.lock();
        try {
            boolean existed = Files.deleteIfExists(chunkFile(chunkId));
            Files.deleteIfExists(sidecarFile(chunkId));
            return existed;
        } finally {
            lock.unlock();
        }
    }

    Path chunkFile(UUID chunkId) {
        return contained(shardDir(chunkId).resolve(chunkId + ".chunk"));
    }

    private Path sidecarFile(UUID chunkId) {
        return contained(shardDir(chunkId).resolve(chunkId + ".sha256"));
    }

    private Path shardDir(UUID chunkId) {
        String s = chunkId.toString();
        return dataDirectory.chunksDir().resolve(s.substring(0, 2)).resolve(s.substring(2, 4));
    }

    /** Defence in depth: the resolved path must stay under chunks/ even though IDs are validated. */
    private Path contained(Path p) {
        Path normalized = p.normalize();
        if (!normalized.startsWith(dataDirectory.chunksDir())) {
            throw new IllegalArgumentException("path escapes data directory");
        }
        return normalized;
    }

    private void writeSidecar(UUID chunkId, String sha) throws IOException {
        Path sidecar = sidecarFile(chunkId);
        Path tmp = Files.createTempFile(dataDirectory.tmpDir(), chunkId + ".", ".sha.part");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.write(java.nio.ByteBuffer.wrap(sha.getBytes(StandardCharsets.US_ASCII)));
                ch.force(true);
            }
            moveAtomically(tmp, sidecar);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveAtomically(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // tmp/ and chunks/ share one volume by construction, so this indicates misconfiguration.
            throw new IOException("atomic rename unsupported between " + from.getParent() + " and " + to.getParent(), e);
        }
    }

    /** Persists the rename itself. Not supported on every platform; failure is logged, not fatal. */
    private static void fsyncDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("directory fsync not supported for {}: {}", dir, e.toString());
        }
    }

    private ReentrantLock lockFor(UUID chunkId) {
        return locks[Math.floorMod(chunkId.hashCode(), LOCK_STRIPES)];
    }

    /** Request body failed length or checksum validation; nothing was stored. */
    public static class ChunkValidationException extends IOException {
        private final String code;

        public ChunkValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** A chunk with this ID already exists with different content. */
    public static class ChunkConflictException extends IOException {
        public ChunkConflictException(UUID chunkId) {
            super("chunk " + chunkId + " already stored with a different checksum");
        }
    }
}
