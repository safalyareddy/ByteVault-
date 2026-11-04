package dev.bytevault.controlplane.placement;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bytevault.controlplane.node.NodeState;
import dev.bytevault.controlplane.placement.PlacementPolicy.Candidate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlacementPolicyTest {

    static final long MIB = 1024 * 1024;
    static final long CHUNK = 4 * MIB;
    static final long RESERVE = 256 * MIB;
    static final String KEY = "0b9a3c1e-6f0c-4c8e-9a51-8d5f3b0c2a11:0";

    static Candidate healthy(String id, long usable) {
        return new Candidate(id, NodeState.HEALTHY, usable);
    }

    @Test
    void picksTopRankedEligibleNodes() {
        var nodes = List.of(healthy("node-1", 10_000 * MIB), healthy("node-2", 10_000 * MIB),
                healthy("node-3", 10_000 * MIB), healthy("node-4", 10_000 * MIB), healthy("node-5", 10_000 * MIB));
        var p = PlacementPolicy.choose(KEY, nodes, 3, CHUNK, RESERVE, Set.of());
        assertThat(p.satisfied()).isTrue();
        // Same order as the rendezvous reference vector for this key.
        assertThat(p.targets()).containsExactly("node-5", "node-1", "node-4");
    }

    @Test
    void excludesNodesBelowChunkPlusReserve() {
        var nodes = List.of(healthy("node-1", 10_000 * MIB), healthy("node-2", 10_000 * MIB),
                healthy("node-3", CHUNK + RESERVE - 1), healthy("node-4", CHUNK + RESERVE));
        var p = PlacementPolicy.choose(KEY, nodes, 3, CHUNK, RESERVE, Set.of());
        assertThat(p.targets()).containsExactlyInAnyOrder("node-1", "node-2", "node-4");
        assertThat(p.excludedForCapacity()).containsExactly("node-3");
    }

    @Test
    void neverSilentlyReturnsFewerThanNeeded() {
        var nodes = List.of(healthy("node-1", 10_000 * MIB), healthy("node-2", 1 * MIB),
                new Candidate("node-3", NodeState.SUSPECT, 10_000 * MIB),
                new Candidate("node-4", NodeState.JOINING, 10_000 * MIB),
                new Candidate("node-5", NodeState.DRAINING, 10_000 * MIB));
        var p = PlacementPolicy.choose(KEY, nodes, 3, CHUNK, RESERVE, Set.of());
        assertThat(p.satisfied()).isFalse();
        assertThat(p.targets()).containsExactly("node-1");
        assertThat(p.eligible()).isEqualTo(1);
        assertThat(p.excludedForCapacity()).containsExactly("node-2");
    }

    @Test
    void nodesAlreadyHoldingAReplicaAreExcluded() {
        var nodes = List.of(healthy("node-1", 10_000 * MIB), healthy("node-2", 10_000 * MIB),
                healthy("node-3", 10_000 * MIB));
        var p = PlacementPolicy.choose(KEY, nodes, 1, CHUNK, RESERVE, Set.of("node-1", "node-2"));
        assertThat(p.targets()).containsExactly("node-3");
    }

    @Test
    void nothingNeededMeansNothingPlaced() {
        var p = PlacementPolicy.choose(KEY, List.of(healthy("node-1", 10_000 * MIB)), 0, CHUNK, RESERVE, Set.of());
        assertThat(p.satisfied()).isTrue();
        assertThat(p.targets()).isEmpty();
    }
}
