package dev.bytevault.controlplane.repair;

import dev.bytevault.controlplane.node.NodeClient;
import dev.bytevault.controlplane.object.ReplicaFaultRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background integrity scrubber. Each cycle asks nodes to re-hash the least recently verified
 * VERIFIED replicas (on HEALTHY nodes) and compares with metadata. Rate-limited by batch size and
 * a pause between chunks so it never saturates the laptop or node disks. Mismatch -> CORRUPT,
 * missing -> LOST; both make the chunk UNDER_REPLICATED so the repair planner picks it up.
 */
@Component
public class Scrubber {

    private static final Logger log = LoggerFactory.getLogger(Scrubber.class);

    private final JdbcClient jdbc;
    private final NodeClient nodeClient;
    private final ReplicaFaultRecorder faults;
    private final RepairProperties props;
    private final MeterRegistry meters;

    public Scrubber(JdbcClient jdbc, NodeClient nodeClient, ReplicaFaultRecorder faults, RepairProperties props,
                    MeterRegistry meters) {
        this.jdbc = jdbc;
        this.nodeClient = nodeClient;
        this.faults = faults;
        this.props = props;
        this.meters = meters;
    }

    public record ScrubSummary(int checked, int intact, int corrupt, int missing, int unavailable) {}

    private record Target(UUID chunkId, UUID objectId, String nodeId, String sha256, int byteSize) {}

    @Scheduled(initialDelay = 30_000, fixedDelayString = "${bytevault.scrub-interval}")
    public void scheduled() {
        if (props.scrub().enabled()) {
            ScrubSummary s = scrub(props.scrub().batchSize());
            if (s.corrupt() + s.missing() > 0) {
                log.warn("Scrub found problems: {}", s);
            } else {
                log.debug("Scrub cycle: {}", s);
            }
        }
    }

    public synchronized ScrubSummary scrub(int limit) {
        List<Target> targets = jdbc.sql("""
                SELECT r.chunk_id, c.object_id, r.node_id, c.sha256, c.byte_size
                  FROM chunk_replicas r
                  JOIN chunks c ON c.id = r.chunk_id
                  JOIN objects o ON o.id = c.object_id
                  JOIN storage_nodes n ON n.id = r.node_id
                 WHERE r.state = 'VERIFIED' AND n.state = 'HEALTHY' AND o.status IN ('COMPLETE', 'DEGRADED')
                 ORDER BY r.last_verified_at NULLS FIRST
                 LIMIT :lim
                """).param("lim", limit).query(Target.class).list();
        int intact = 0, corrupt = 0, missing = 0, unavailable = 0;
        for (Target t : targets) {
            NodeClient.VerifyResult v = nodeClient.verifyChunk(t.nodeId(), t.chunkId());
            if (v.matches(t.sha256(), t.byteSize())) {
                jdbc.sql("UPDATE chunk_replicas SET last_verified_at = now() WHERE chunk_id = :c AND node_id = :n "
                        + "AND state = 'VERIFIED'").param("c", t.chunkId()).param("n", t.nodeId()).update();
                intact++;
            } else {
                switch (v.status()) {
                    case OK -> {
                        corrupt++;
                        meters.counter("bv_checksum_failures_total", "path", "scrub").increment();
                        faults.record(t.objectId(), t.chunkId(), t.nodeId(), ReplicaFaultRecorder.Fault.CORRUPT,
                                t.sha256(), v.actualSha256(), "scrub: on-disk bytes do not match metadata");
                    }
                    case MISSING -> {
                        missing++;
                        faults.record(t.objectId(), t.chunkId(), t.nodeId(), ReplicaFaultRecorder.Fault.LOST,
                                t.sha256(), null, "scrub: chunk missing on node");
                    }
                    default -> unavailable++;
                }
            }
            pause();
        }
        ScrubSummary summary = new ScrubSummary(targets.size(), intact, corrupt, missing, unavailable);
        meters.counter("bv_scrub_replicas_checked_total").increment(summary.checked());
        return summary;
    }

    private void pause() {
        long ms = props.scrub().pauseBetweenChunks().toMillis();
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
