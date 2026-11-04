package dev.bytevault.controlplane.node;

import static dev.bytevault.controlplane.node.NodeState.DRAINING;
import static dev.bytevault.controlplane.node.NodeState.HEALTHY;
import static dev.bytevault.controlplane.node.NodeState.JOINING;
import static dev.bytevault.controlplane.node.NodeState.OFFLINE;
import static dev.bytevault.controlplane.node.NodeState.SUSPECT;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure node-lifecycle rules (docs/state-machines.md, ADR-0010). No I/O, so every decision is unit
 * tested; {@link NodeLifecycleService} applies them with compare-and-set writes.
 */
public final class NodeLifecycle {

    private static final Map<NodeState, Set<NodeState>> ALLOWED = Map.of(
            JOINING, EnumSet.of(HEALTHY),
            HEALTHY, EnumSet.of(SUSPECT, DRAINING),
            SUSPECT, EnumSet.of(HEALTHY, OFFLINE, DRAINING),
            OFFLINE, EnumSet.of(JOINING),
            DRAINING, EnumSet.of(HEALTHY));

    private NodeLifecycle() {}

    public static boolean isAllowed(NodeState from, NodeState to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void requireAllowed(NodeState from, NodeState to) {
        if (!isAllowed(from, to)) {
            throw new IllegalStateException("invalid node transition " + from + " -> " + to);
        }
    }

    public enum HeartbeatAction { NONE, RUN_ADMISSION_PROBE, MARK_HEALTHY, MARK_SUSPECT, MARK_JOINING }

    /**
     * Decision for a valid, authenticated heartbeat.
     *
     * @param reportsUp node says UP, data dir writable, and protocol version supported
     * @param consecutiveUp consecutive UP heartbeats including this one
     */
    public static HeartbeatAction onHeartbeat(NodeState state, boolean reportsUp, int consecutiveUp,
                                              int admissionHeartbeats) {
        return switch (state) {
            case JOINING -> reportsUp && consecutiveUp >= admissionHeartbeats
                    ? HeartbeatAction.RUN_ADMISSION_PROBE : HeartbeatAction.NONE;
            case HEALTHY -> reportsUp ? HeartbeatAction.NONE : HeartbeatAction.MARK_SUSPECT;
            case SUSPECT -> reportsUp ? HeartbeatAction.MARK_HEALTHY : HeartbeatAction.NONE;
            case OFFLINE -> reportsUp ? HeartbeatAction.MARK_JOINING : HeartbeatAction.NONE;
            case DRAINING -> HeartbeatAction.NONE;
        };
    }

    /** True when a HEALTHY node has been silent longer than the suspect threshold. */
    public static boolean shouldSuspect(NodeState state, Duration heartbeatAge, Duration suspectAfter) {
        return state == HEALTHY && (heartbeatAge == null || heartbeatAge.compareTo(suspectAfter) > 0);
    }
}
