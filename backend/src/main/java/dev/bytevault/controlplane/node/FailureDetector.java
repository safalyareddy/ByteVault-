package dev.bytevault.controlplane.node;

import dev.bytevault.controlplane.config.BytevaultProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Heartbeat-age failure detector (ADR-0010): a HEALTHY node silent for longer than
 * {@code suspect-after} becomes SUSPECT. Also publishes per-node gauges from the same reading.
 * OFFLINE detection, which marks replicas LOST, is Phase 5.
 */
@Component
public class FailureDetector {

    private final NodeRepository nodes;
    private final NodeLifecycleService lifecycle;
    private final Duration suspectAfter;
    private final MeterRegistry meters;
    private final Map<String, NodeGauges> gauges = new ConcurrentHashMap<>();

    public FailureDetector(NodeRepository nodes, NodeLifecycleService lifecycle, BytevaultProperties props,
                           MeterRegistry meters) {
        this.nodes = nodes;
        this.lifecycle = lifecycle;
        this.suspectAfter = props.suspectAfter();
        this.meters = meters;
    }

    @Scheduled(initialDelay = 1_000, fixedDelayString = "${bytevault.failure-detector-interval:1s}")
    public void evaluate() {
        OffsetDateTime now = OffsetDateTime.now();
        for (NodeRepository.NodeRow row : nodes.findAll()) {
            Duration age = row.lastHeartbeatAt() == null ? null : Duration.between(row.lastHeartbeatAt(), now);
            gauges.computeIfAbsent(row.id(), this::register).update(row, age);
            if (NodeLifecycle.shouldSuspect(row.state(), age, suspectAfter)) {
                lifecycle.transition(row, NodeState.SUSPECT,
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
