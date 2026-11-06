package dev.bytevault.controlplane.upload;

import dev.bytevault.controlplane.config.ClusterProperties;
import dev.bytevault.controlplane.web.ApiException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Backpressure for chunk transfers (uploads and downloads). At most N chunks are spooled at once,
 * so temp-disk use is bounded by N x chunk size. Requests wait briefly for a permit, then get 503.
 */
@Component
public class TransferPermits {

    private final Semaphore permits;
    private final long waitMillis;
    private final Path spoolDir;

    public TransferPermits(ClusterProperties cluster, MeterRegistry meters) {
        this.permits = new Semaphore(cluster.getMaxConcurrentChunkTransfers(), true);
        this.waitMillis = cluster.getTransferPermitWait().toMillis();
        try {
            this.spoolDir = Files.createDirectories(cluster.getSpoolDir());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create spool dir", e);
        }
        Gauge.builder("bv_chunk_transfers_in_flight",
                        () -> cluster.getMaxConcurrentChunkTransfers() - permits.availablePermits())
                .register(meters);
    }

    public Permit acquire() {
        try {
            if (!permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BUSY",
                        "Too many concurrent chunk transfers; retry later", Map.of("retryAfterSeconds", 5));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BUSY", "Interrupted while waiting");
        }
        return new Permit();
    }

    /** A held permit plus a private spool file; closing releases both. */
    public final class Permit implements AutoCloseable {
        private Path file;
        private boolean closed;

        public Path spoolFile() throws IOException {
            if (file == null) {
                file = Files.createTempFile(spoolDir, "chunk-", ".spool");
            }
            return file;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (file != null) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException ignored) {
                // a leftover temp file is harmless and is in a dedicated directory
            } finally {
                permits.release();
            }
        }
    }
}
