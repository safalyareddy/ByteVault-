package dev.bytevault.controlplane.repair;

import dev.bytevault.controlplane.config.ClusterProperties;
import dev.bytevault.controlplane.events.JitteredBackOff;
import dev.bytevault.controlplane.events.Topics;
import dev.bytevault.controlplane.node.NodeClient;
import dev.bytevault.controlplane.node.NodeRepository;
import dev.bytevault.controlplane.object.ReplicaFaultRecorder;
import dev.bytevault.controlplane.outbox.OutboxWriter;
import dev.bytevault.controlplane.placement.PlacementPolicy;
import dev.bytevault.controlplane.placement.RendezvousPlacement;
import dev.bytevault.controlplane.upload.TransferPermits;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes repair jobs (ADR-0007): claim with a lease (compare-and-set), read the chunk from a
 * VERIFIED source while re-hashing it, write it to an eligible target, have the target re-hash it
 * from disk, and only then commit the new VERIFIED replica and retire superseded copies.
 *
 * <p>No transaction is open during transfers. Every step is idempotent (node PUT is idempotent by
 * chunk ID and checksum), so a worker that dies mid-job simply lets its lease expire and another
 * claim redoes the job.
 */
@Component
public class RepairWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RepairWorker.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;
    private final NodeRepository nodes;
    private final NodeClient nodeClient;
    private final TransferPermits permits;
    private final ReplicaFaultRecorder faults;
    private final ReplicationStatus status;
    private final RepairProperties props;
    private final ClusterProperties cluster;
    private final MeterRegistry meters;
    private final Timer duration;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final JitteredBackOff retryBackOff = new JitteredBackOff(2_000, 2.0, 60_000, Integer.MAX_VALUE, 0.2);
    private final String instanceId = "cp-" + UUID.randomUUID().toString().substring(0, 8);

    public RepairWorker(JdbcClient jdbc, TransactionTemplate tx, OutboxWriter outbox, NodeRepository nodes,
                        NodeClient nodeClient, TransferPermits permits, ReplicaFaultRecorder faults,
                        ReplicationStatus status, RepairProperties props, ClusterProperties cluster,
                        MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.nodes = nodes;
        this.nodeClient = nodeClient;
        this.permits = permits;
        this.faults = faults;
        this.status = status;
        this.props = props;
        this.cluster = cluster;
        this.meters = meters;
        this.duration = Timer.builder("bv_repair_duration_seconds").publishPercentiles(0.5, 0.95).register(meters);
        this.executor = Executors.newFixedThreadPool(props.maxConcurrent(), new CustomizableThreadFactory("repair-"));
        Gauge.builder("bv_repair_jobs_in_flight", inFlight, AtomicInteger::get).register(meters);
    }

    @Scheduled(initialDelay = 4_000, fixedDelayString = "${bytevault.repair.worker-interval:1s}")
    public void dispatch() {
        if (!props.enabled()) {
            return;
        }
        while (inFlight.get() < props.maxConcurrent()) {
            Optional<RepairJob> job = claim();
            if (job.isEmpty()) {
                return;
            }
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try {
                    execute(job.get());
                } catch (RuntimeException e) {
                    log.error("Repair job {} crashed", job.get().id(), e);
                    fail(job.get(), "unexpected error: " + e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    Optional<RepairJob> claim() {
        return jdbc.sql("""
                UPDATE repair_jobs SET state = 'CLAIMED', lease_owner = :owner,
                       lease_until = now() + make_interval(secs => :lease), attempts = attempts + 1,
                       started_at = COALESCE(started_at, now()), updated_at = now(), version = version + 1
                 WHERE id = (SELECT id FROM repair_jobs
                              WHERE (state = 'PENDING' AND next_attempt_at <= now())
                                 OR (state IN ('CLAIMED', 'COPYING', 'VERIFYING') AND lease_until < now())
                              ORDER BY next_attempt_at, created_at
                              LIMIT 1
                                FOR UPDATE SKIP LOCKED)
                RETURNING
                """ + " " + RepairJob.COLUMNS)
                .param("owner", instanceId).param("lease", props.lease().toSeconds())
                .query(RepairJob.class).optional();
    }

    private record ChunkCtx(UUID id, UUID objectId, int chunkIndex, int byteSize, String sha256, String status,
                            String objectStatus, int replicationFactor) {}

    private record ReplicaCtx(String nodeId, String state, String nodeState) {}

    void execute(RepairJob job) {
        Timer.Sample sample = Timer.start();
        ChunkCtx chunk = jdbc.sql("""
                SELECT c.id, c.object_id, c.chunk_index, c.byte_size, c.sha256, c.status, o.status AS object_status,
                       o.replication_factor
                  FROM chunks c JOIN objects o ON o.id = c.object_id WHERE c.id = :c
                """).param("c", job.chunkId()).query(ChunkCtx.class).single();
        if (!Set.of("COMPLETE", "DEGRADED").contains(chunk.objectStatus())
                || !Set.of("STORED", "UNDER_REPLICATED").contains(chunk.status())) {
            finish(job, chunk, "chunk no longer needs repair (object " + chunk.objectStatus() + ")", "noop");
            return;
        }
        List<ReplicaCtx> replicas = jdbc.sql("""
                SELECT r.node_id, r.state, n.state AS node_state
                  FROM chunk_replicas r JOIN storage_nodes n ON n.id = r.node_id WHERE r.chunk_id = :c
                """).param("c", chunk.id()).query(ReplicaCtx.class).list();
        long active = replicas.stream().filter(r -> r.state().equals("VERIFIED") && !r.nodeState().equals("DRAINING"))
                .count();
        if (active >= chunk.replicationFactor()) {
            finish(job, chunk, "already satisfied", "noop");
            return;
        }

        // Target: eligible HEALTHY node with capacity that holds no VERIFIED/PENDING copy and is not
        // the target of another active repair (one repair per target node at a time).
        Set<String> busyTargets = Set.copyOf(jdbc.sql("""
                SELECT target_node_id FROM repair_jobs
                 WHERE id <> :id AND target_node_id IS NOT NULL AND state IN ('CLAIMED', 'COPYING', 'VERIFYING')
                   AND lease_until > now()
                """).param("id", job.id()).query(String.class).list());
        Set<String> exclude = replicas.stream().filter(r -> Set.of("VERIFIED", "PENDING", "DELETING").contains(r.state()))
                .map(ReplicaCtx::nodeId).collect(Collectors.toSet());
        exclude.addAll(busyTargets);
        List<PlacementPolicy.Candidate> candidates = nodes.findAll().stream()
                .map(n -> new PlacementPolicy.Candidate(n.id(), n.state(), n.usableBytes())).toList();
        PlacementPolicy.Placement placement = PlacementPolicy.choose(
                RendezvousPlacement.chunkKey(chunk.objectId(), chunk.chunkIndex()), candidates, 1, chunk.byteSize(),
                cluster.getNodeFreeSpaceReserveBytes(), exclude);
        if (!placement.satisfied()) {
            waitForCapacity(job, "no eligible target node (healthy nodes without a copy: " + placement.eligible()
                    + ", excluded for capacity: " + placement.excludedForCapacity() + ")");
            return;
        }
        String target = placement.targets().get(0);

        List<ReplicaCtx> sources = replicas.stream().filter(r -> r.state().equals("VERIFIED"))
                .sorted((a, b) -> Integer.compare(sourceRank(a.nodeState()), sourceRank(b.nodeState())))
                .toList();
        try (TransferPermits.Permit permit = permits.acquire()) {
            Path spool = permit.spoolFile();
            String source = null;
            for (ReplicaCtx candidate : sources) {
                NodeClient.FetchResult r = nodeClient.fetchChunk(candidate.nodeId(), chunk.id(), chunk.byteSize(),
                        chunk.sha256(), spool);
                switch (r.status()) {
                    case OK -> source = candidate.nodeId();
                    case CORRUPT -> {
                        meters.counter("bv_checksum_failures_total", "path", "repair_source").increment();
                        faults.record(chunk.objectId(), chunk.id(), candidate.nodeId(),
                                ReplicaFaultRecorder.Fault.CORRUPT, chunk.sha256(), r.actualSha256(), r.error());
                    }
                    case MISSING -> faults.record(chunk.objectId(), chunk.id(), candidate.nodeId(),
                            ReplicaFaultRecorder.Fault.LOST, chunk.sha256(), null, r.error());
                    case UNAVAILABLE -> log.debug("Repair source {} unavailable: {}", candidate.nodeId(), r.error());
                }
                if (source != null) {
                    break;
                }
            }
            if (source == null) {
                fail(job, "no VERIFIED source replica could be read and verified");
                return;
            }
            final String src = source;
            if (!advance(job, "CLAIMED", "COPYING", src, target)) {
                return; // lease lost to another worker
            }
            String previous = jdbc.sql("SELECT state FROM chunk_replicas WHERE chunk_id = :c AND node_id = :n")
                    .param("c", chunk.id()).param("n", target).query(String.class).optional().orElse(null);
            if ("CORRUPT".equals(previous) && !nodeClient.deleteChunk(target, chunk.id())) {
                fail(job, "could not remove corrupt bytes on target " + target);
                return;
            }
            tx.executeWithoutResult(t -> jdbc.sql("""
                    INSERT INTO chunk_replicas (chunk_id, node_id, state) VALUES (:c, :n, 'PENDING')
                    ON CONFLICT (chunk_id, node_id) DO UPDATE
                       SET state = 'PENDING', last_error = NULL, updated_at = now(), version = chunk_replicas.version + 1
                     WHERE chunk_replicas.state IN ('PENDING', 'FAILED_WRITE', 'CORRUPT', 'LOST', 'DELETED')
                    """).param("c", chunk.id()).param("n", target).update());

            NodeClient.PutResult put = nodeClient.putChunk(target, chunk.id(), spool, chunk.sha256());
            if (!put.success()) {
                markTargetFailed(chunk.id(), target, put.error());
                fail(job, "write to " + target + " failed: " + put.error());
                return;
            }
            if (!advance(job, "COPYING", "VERIFYING", src, target)) {
                return;
            }
            NodeClient.VerifyResult check = nodeClient.verifyChunk(target, chunk.id());
            if (!check.matches(chunk.sha256(), chunk.byteSize())) {
                String why = check.status() + (check.error() == null ? "" : " " + check.error())
                        + " actual=" + check.actualSha256();
                markTargetFailed(chunk.id(), target, "target verification failed: " + why);
                fail(job, "target " + target + " did not verify: " + why);
                return;
            }
            commit(job, chunk, src, target);
            sample.stop(duration);
        } catch (RuntimeException e) {
            fail(job, e.toString());
        } catch (java.io.IOException e) {
            fail(job, "spool I/O: " + e.getMessage());
        }
    }

    private static int sourceRank(String nodeState) {
        return switch (nodeState) {
            case "HEALTHY" -> 0;
            case "DRAINING" -> 1;
            default -> 2;
        };
    }

    private boolean advance(RepairJob job, String from, String to, String source, String target) {
        return jdbc.sql("""
                UPDATE repair_jobs SET state = :to, source_node_id = :src, target_node_id = :tgt,
                       lease_until = now() + make_interval(secs => :lease), updated_at = now(), version = version + 1
                 WHERE id = :id AND state = :from AND lease_owner = :owner
                """).param("to", to).param("src", source).param("tgt", target).param("lease", props.lease().toSeconds())
                .param("id", job.id()).param("from", from).param("owner", instanceId).update() == 1;
    }

    private void commit(RepairJob job, ChunkCtx chunk, String source, String target) {
        tx.executeWithoutResult(t -> {
            jdbc.sql("""
                    UPDATE chunk_replicas
                       SET state = 'VERIFIED', stored_checksum = :sha, stored_at = now(), last_verified_at = now(),
                           last_error = NULL, updated_at = now(), version = version + 1
                     WHERE chunk_id = :c AND node_id = :n AND state = 'PENDING'
                    """).param("sha", chunk.sha256()).param("c", chunk.id()).param("n", target).update();
            jdbc.sql("""
                    UPDATE repair_jobs SET state = 'COMMITTED', completed_at = now(), lease_owner = NULL,
                           lease_until = NULL, last_error = NULL, updated_at = now(), version = version + 1
                     WHERE id = :id
                    """).param("id", job.id()).update();
            int retired = status.retireSuperseded(chunk.id());
            status.refresh(chunk.id());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("repairJobId", job.id());
            payload.put("chunkId", chunk.id());
            payload.put("objectId", chunk.objectId());
            payload.put("sourceNodeId", source);
            payload.put("targetNodeId", target);
            payload.put("attempts", job.attempts());
            payload.put("retiredReplicas", retired);
            outbox.append("REPAIR_JOB", job.id().toString(), 1, "RepairCommitted", Topics.REPAIR_RESULTS,
                    job.id().toString(), payload);
        });
        meters.counter("bv_repair_jobs_total", "result", "committed").increment();
        log.info("Repaired chunk {} of object {}: {} -> {}", chunk.chunkIndex(), chunk.objectId(), source, target);
    }

    private void finish(RepairJob job, ChunkCtx chunk, String note, String result) {
        tx.executeWithoutResult(t -> {
            jdbc.sql("""
                    UPDATE repair_jobs SET state = 'COMMITTED', completed_at = now(), lease_owner = NULL,
                           lease_until = NULL, last_error = :note, updated_at = now(), version = version + 1
                     WHERE id = :id
                    """).param("note", note).param("id", job.id()).update();
            status.retireSuperseded(chunk.id());
            status.refresh(chunk.id());
        });
        meters.counter("bv_repair_jobs_total", "result", result).increment();
    }

    /** No target available: not the job's fault, so the attempt is not counted. */
    private void waitForCapacity(RepairJob job, String reason) {
        jdbc.sql("""
                UPDATE repair_jobs SET state = 'PENDING', attempts = GREATEST(attempts - 1, 0), last_error = :err,
                       lease_owner = NULL, lease_until = NULL,
                       next_attempt_at = now() + make_interval(secs => :wait), updated_at = now(), version = version + 1
                 WHERE id = :id AND lease_owner = :owner
                """).param("err", "waiting for capacity: " + reason).param("wait", props.capacityWait().toSeconds())
                .param("id", job.id()).param("owner", instanceId).update();
        meters.counter("bv_repair_jobs_total", "result", "waiting_for_capacity").increment();
    }

    private void markTargetFailed(UUID chunkId, String target, String error) {
        jdbc.sql("""
                UPDATE chunk_replicas SET state = 'FAILED_WRITE', last_error = :err, updated_at = now(),
                       version = version + 1
                 WHERE chunk_id = :c AND node_id = :n AND state = 'PENDING'
                """).param("err", error).param("c", chunkId).param("n", target).update();
    }

    private void fail(RepairJob job, String error) {
        boolean abandon = job.attempts() >= job.maxAttempts();
        long delayMs = retryBackOff.jittered(retryBackOff.baseDelay(Math.max(job.attempts() - 1, 0)));
        tx.executeWithoutResult(t -> {
            int updated = jdbc.sql("""
                    UPDATE repair_jobs
                       SET state = :state, last_error = :err, lease_owner = NULL, lease_until = NULL,
                           next_attempt_at = now() + make_interval(secs => :delay),
                           completed_at = CASE WHEN :state = 'ABANDONED' THEN now() ELSE NULL END,
                           updated_at = now(), version = version + 1
                     WHERE id = :id AND state NOT IN ('COMMITTED', 'ABANDONED')
                    """).param("state", abandon ? "ABANDONED" : "PENDING").param("err", truncate(error))
                    .param("delay", delayMs / 1000.0).param("id", job.id()).update();
            if (updated == 1) {
                outbox.append("REPAIR_JOB", job.id().toString(), job.attempts(),
                        abandon ? "RepairAbandoned" : "RepairFailed", Topics.REPAIR_RESULTS, job.id().toString(),
                        Map.of("repairJobId", job.id(), "chunkId", job.chunkId(), "attempts", job.attempts(),
                                "error", truncate(error)));
            }
        });
        meters.counter("bv_repair_jobs_total", "result", abandon ? "abandoned" : "failed").increment();
        log.warn("Repair job {} attempt {}/{} failed{}: {}", job.id(), job.attempts(), job.maxAttempts(),
                abandon ? " (abandoned)" : ", retrying in " + Duration.ofMillis(delayMs).toSeconds() + "s", error);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
