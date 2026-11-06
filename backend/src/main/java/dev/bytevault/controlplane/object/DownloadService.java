package dev.bytevault.controlplane.object;

import dev.bytevault.controlplane.node.NodeClient;
import dev.bytevault.controlplane.object.ObjectDtos.ObjectSummary;
import dev.bytevault.controlplane.upload.TransferPermits;
import dev.bytevault.controlplane.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reassembles an object from verified chunks. Each chunk is fetched into a bounded spool file and
 * checked against metadata before any of its bytes reach the client, so corrupted bytes are never
 * sent. If no replica of a chunk verifies, the response is aborted; because Content-Length is the
 * full object size, the client can detect the truncation.
 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private final JdbcClient jdbc;
    private final NodeClient nodeClient;
    private final TransferPermits permits;
    private final ReplicaFaultRecorder faults;
    private final MeterRegistry meters;

    public DownloadService(JdbcClient jdbc, NodeClient nodeClient, TransferPermits permits,
                           ReplicaFaultRecorder faults, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.nodeClient = nodeClient;
        this.permits = permits;
        this.faults = faults;
        this.meters = meters;
    }

    public void requireDownloadable(ObjectSummary object) {
        if (!object.status().equals("COMPLETE") && !object.status().equals("DEGRADED")) {
            throw new ApiException(HttpStatus.CONFLICT, "OBJECT_NOT_DOWNLOADABLE",
                    "object is " + object.status() + "; only COMPLETE or DEGRADED objects can be downloaded");
        }
    }

    private record ChunkRow(UUID id, int chunkIndex, int byteSize, String sha256) {}

    /** A VERIFIED replica, best candidates first: replicas on HEALTHY nodes, then by node ID. */
    private record SourceRow(String nodeId) {}

    public void stream(UUID objectId, OutputStream out) throws IOException {
        List<ChunkRow> chunks = jdbc.sql("SELECT id, chunk_index, byte_size, sha256 FROM chunks "
                        + "WHERE object_id = :o ORDER BY chunk_index").param("o", objectId).query(ChunkRow.class).list();
        for (ChunkRow chunk : chunks) {
            try (TransferPermits.Permit permit = permits.acquire()) {
                Path spool = permit.spoolFile();
                if (!fetchVerified(objectId, chunk, spool)) {
                    meters.counter("bv_download_failures_total", "reason", "no_verified_replica").increment();
                    throw new IOException("no verified replica available for chunk " + chunk.chunkIndex()
                            + " of object " + objectId);
                }
                Files.copy(spool, out);
            }
        }
        out.flush();
    }

    private boolean fetchVerified(UUID objectId, ChunkRow chunk, Path spool) {
        List<SourceRow> sources = jdbc.sql("""
                SELECT r.node_id FROM chunk_replicas r JOIN storage_nodes n ON n.id = r.node_id
                 WHERE r.chunk_id = :c AND r.state = 'VERIFIED'
                 ORDER BY CASE n.state WHEN 'HEALTHY' THEN 0 WHEN 'DRAINING' THEN 1 ELSE 2 END, r.node_id
                """).param("c", chunk.id()).query(SourceRow.class).list();
        for (SourceRow source : sources) {
            NodeClient.FetchResult r = nodeClient.fetchChunk(source.nodeId(), chunk.id(), chunk.byteSize(),
                    chunk.sha256(), spool);
            switch (r.status()) {
                case OK -> {
                    return true;
                }
                case CORRUPT -> {
                    meters.counter("bv_checksum_failures_total", "path", "read").increment();
                    faults.record(objectId, chunk.id(), source.nodeId(), ReplicaFaultRecorder.Fault.CORRUPT,
                            chunk.sha256(), r.actualSha256(), r.error());
                }
                case MISSING -> faults.record(objectId, chunk.id(), source.nodeId(), ReplicaFaultRecorder.Fault.LOST,
                        chunk.sha256(), null, r.error());
                case UNAVAILABLE -> log.warn("Replica of chunk {} on node {} unavailable: {}", chunk.id(),
                        source.nodeId(), r.error());
            }
        }
        return false;
    }
}
