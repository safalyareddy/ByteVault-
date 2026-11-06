package dev.bytevault.controlplane.node;

import dev.bytevault.controlplane.config.BytevaultProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Heartbeat-age failure detector (ADR-0010): a HEALTHY node silent for longer than
 * {@code suspect-after} becomes SUSPECT. Also publishes per-node gauges from the same reading.
 * A SUSPECT node silent for longer than {@code offline-after} becomes OFFLINE (replicas LOST).
 *
 * <p>If the detector itself was paused (the host slept or the VM froze), every heartbeat looks
 * old at once. It then waits one {@code suspect-after} window before judging again, instead of
 * declaring every node failed. Found in practice: a sleeping laptop marked all 4 nodes OFFLINE
 * on each wake-up. The same grace applies after the control plane starts, which is also a period
 * in which no heartbeats were received (found when restarting it after an 80 s stop).
 */
@Component
public class FailureDetector {

    private static final Logger log = LoggerFactory.getLogger(FailureDetector.class);

    private final NodeRepository nodes;
    private final NodeLifecycleService lifecycle;
    private final Duration suspectAfter;
    private final Duration offlineAfter;
    private final MeterRegistry meters;
    private final Map<String, NodeGauges> gauges = new ConcurrentHashMap<>();
    private final Duration evaluationInterval;
    private final io.micrometer.core.instrument.Counter pauses;
    private volatile Instant lastEvaluation;
    private volatile Instant graceUntil;

    public FailureDetector(NodeRepository nodes, NodeLifecycleService lifecycle, BytevaultProperties props,
                           MeterRegistry meters,
                           @org.springframework.beans.factory.annotation.Value("${bytevault.failure-detector-interval:1s}")
                           Duration evaluationInterval) {
        this.evaluationInterval = evaluationInterval;
        // A freshly started control plane was absent for an unknown time: heartbeats sent while it
        // was down were never recorded, so stored ages say nothing about the nodes yet.
        this.graceUntil = Instant.now().plus(props.suspectAfter());
        this.pauses = io.micrometer.core.instrument.Counter.builder("bv_failure_detector_pauses_total")
                .description("Times the detector found itself paused and suspended judgement").register(meters);
        this.nodes = nodes;
        this.lifecycle = lifecycle;
        this.suspectAfter = props.suspectAfter();
        this.offlineAfter = props.offlineAfter();
        this.meters = meters;
    }

    @Scheduled(initialDelay = 1_000, fixedDelayString = "${bytevault.failure-detector-interval:1s}")
    public void evaluate() {
        OffsetDateTime now = OffsetDateTime.now();
        Instant wall = Instant.now();
        if (lastEvaluation != null) {
            Duration gap = Duration.between(lastEvaluation, wall);
            if (NodeLifecycle.observerPaused(gap, evaluationInterval)) {
                graceUntil = wall.plus(suspectAfter);
                pauses.increment();
                log.warn("Failure detector was paused for {}s (host sleep or VM freeze?). Not judging heartbeat "
                        + "ages for {}s so nodes can report again.", gap.toSeconds(), suspectAfter.toSeconds());
            }
        }
        lastEvaluation = wall;
        boolean inGrace = wall.isBefore(graceUntil);
        for (NodeRepository.NodeRow row : nodes.findAll()) {
            Duration age = row.lastHeartbeatAt() == null ? null : Duration.between(row.lastHeartbeatAt(), now);
            gauges.computeIfAbsent(row.id(), this::register).update(row, age);
            if (inGrace) {
                continue;
            }
            if (NodeLifecycle.shouldSuspect(row.state(), age, suspectAfter)) {
                lifecycle.transition(row, NodeState.SUSPECT,
                        age == null ? "no heartbeat recorded" : "no heartbeat for " + age.toSeconds() + "s");
            } else if (NodeLifecycle.shouldMarkOffline(row.state(), age, offlineAfter)) {
                lifecycle.markOffline(row,
                        age == null ? "no heartbeat recorded" : "no heartbeat for " + age.toSeconds() + "s");
            }
        }
    }

    /** Gauges per node. {@code bv_node_state} is 1 for the node's current state and 0 otherwise. */
    private NodeGauges register(String nodeId) {
        NodeGauges g = new NodeGauges();
        Gauge.builder("bv_node_heartbeat_age_seconds", g.ageSeconds, AtomicLong::get).tag("node", nodeId)
                .description("Seconds since the node's last accepted heartbeat (-1 if none)").register(meters);
        Gauge.builder("bv_node_capacity_bytes", g.capacityBytes, AtomicLong::get).tag("node", nodeId).register(meters);
        Gauge.builder("bv_node_usable_bytes", g.usableBytes, AtomicLong::get).tag("node", nodeId).register(meters);
        for (NodeState s : NodeState.values()) {
            Gauge.builder("bv_node_state", () -> g.state == s ? 1 : 0)
                    .tags("node", nodeId, "state", s.name()).register(meters);
        }
        return g;
    }

    private static final class NodeGauges {
        final AtomicLong ageSeconds = new AtomicLong(-1);
        final AtomicLong capacityBytes = new AtomicLong();
        final AtomicLong usableBytes = new AtomicLong();
        volatile NodeState state;

        void update(NodeRepository.NodeRow row, Duration age) {
            ageSeconds.set(age == null ? -1 : age.toSeconds());
            capacityBytes.set(row.capacityBytes());
            usableBytes.set(row.usableBytes());
            state = row.state();
        }
    }
}
