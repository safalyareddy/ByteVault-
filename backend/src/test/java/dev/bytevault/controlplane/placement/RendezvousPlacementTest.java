package dev.bytevault.controlplane.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RendezvousPlacementTest {

    private static final List<String> NODES = List.of("node-1", "node-2", "node-3", "node-4", "node-5");

    /**
     * Expected orderings computed independently with Python's hashlib using the ADR-0004 formula:
     * sorted(nodes, key=lambda n: (-int.from_bytes(sha256(f"{key}|{n}").digest()[:8], "big"), n)).
     */
    @Test
    void matchesIndependentReferenceImplementation() {
        assertThat(RendezvousPlacement.rank("0b9a3c1e-6f0c-4c8e-9a51-8d5f3b0c2a11:0", NODES))
                .containsExactly("node-5", "node-1", "node-4", "node-2", "node-3");
        assertThat(RendezvousPlacement.rank("0b9a3c1e-6f0c-4c8e-9a51-8d5f3b0c2a11:1", NODES))
                .containsExactly("node-5", "node-3", "node-1", "node-2", "node-4");
        assertThat(RendezvousPlacement.rank("4f6e2d10-1111-4222-8333-944455556666:7", NODES))
                .containsExactly("node-5", "node-2", "node-3", "node-1", "node-4");
    }

    @Test
    void independentOfInputOrderAndDuplicates() {
        String key = RendezvousPlacement.chunkKey(UUID.randomUUID(), 3);
        List<String> shuffled = new ArrayList<>(NODES);
        shuffled.add("node-2");
        Collections.shuffle(shuffled, new Random(42));
        assertThat(RendezvousPlacement.rank(key, shuffled)).isEqualTo(RendezvousPlacement.rank(key, NODES));
    }

    @Test
    void resultIsDistinctNodes() {
        for (int i = 0; i < 500; i++) {
            List<String> ranked = RendezvousPlacement.rank(RendezvousPlacement.chunkKey(UUID.randomUUID(), i), NODES);
            assertThat(ranked).doesNotHaveDuplicates().hasSize(NODES.size());
        }
    }

    @Test
    void removingANodeOnlyMovesChunksThatHadIt() {
        List<String> without = NODES.stream().filter(n -> !n.equals("node-4")).toList();
        for (int i = 0; i < 500; i++) {
            String key = RendezvousPlacement.chunkKey(UUID.randomUUID(), i);
            List<String> before = RendezvousPlacement.rank(key, NODES).subList(0, 3);
            List<String> after = RendezvousPlacement.rank(key, without).subList(0, 3);
            if (!before.contains("node-4")) {
                assertThat(after).isEqualTo(before);
            } else {
                assertThat(after).containsAll(before.stream().filter(n -> !n.equals("node-4")).toList());
            }
        }
    }

    @Test
    void spreadsLoadRoughlyEvenly() {
        int[] firstChoice = new int[NODES.size()];
        int samples = 10_000;
        for (int i = 0; i < samples; i++) {
            String first = RendezvousPlacement.rank(RendezvousPlacement.chunkKey(UUID.randomUUID(), i), NODES).get(0);
            firstChoice[NODES.indexOf(first)]++;
        }
        for (int count : firstChoice) {
            assertThat(count).isBetween(samples / 5 - 400, samples / 5 + 400);
        }
    }
}
