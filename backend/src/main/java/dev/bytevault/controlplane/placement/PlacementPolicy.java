package dev.bytevault.controlplane.placement;

import dev.bytevault.controlplane.node.NodeState;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Chooses replica targets (ADR-0004): filter to eligible nodes, rank by rendezvous hash, take the
 * first {@code needed}. Never returns fewer targets silently; the caller checks
 * {@link Placement#satisfied()}.
 *
 * <p>Eligible = HEALTHY, not excluded (already holds a replica), and reports at least
 * {@code chunkBytes + reserveBytes} usable space. Capacity is whatever the node last reported in
 * its heartbeat.
 */
public final class PlacementPolicy {

    private PlacementPolicy() {}

    public record Candidate(String nodeId, NodeState state, long usableBytes) {}

    public record Placement(List<String> targets, int needed, int eligible, List<String> excludedForCapacity) {
        public boolean satisfied() {
            return targets.size() == needed;
        }
    }

    public static Placement choose(String key, Collection<Candidate> nodes, int needed, long chunkBytes,
                                   long reserveBytes, Set<String> exclude) {
        if (needed <= 0) {
            return new Placement(List.of(), 0, 0, List.of());
        }
        List<Candidate> healthy = nodes.stream()
                .filter(n -> n.state() == NodeState.HEALTHY && !exclude.contains(n.nodeId()))
                .toList();
        List<String> lowCapacity = healthy.stream()
                .filter(n -> n.usableBytes() < chunkBytes + reserveBytes)
                .map(Candidate::nodeId)
                .sorted()
                .toList();
        List<String> eligible = healthy.stream()
                .map(Candidate::nodeId)
                .filter(id -> !lowCapacity.contains(id))
                .toList();
        List<String> ranked = RendezvousPlacement.rank(key, eligible);
        return new Placement(ranked.subList(0, Math.min(needed, ranked.size())), needed, ranked.size(), lowCapacity);
    }
}
