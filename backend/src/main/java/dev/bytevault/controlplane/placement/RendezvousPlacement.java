package dev.bytevault.controlplane.placement;

import dev.bytevault.protocol.Sha256;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Rendezvous (highest-random-weight) hashing, ADR-0004. Pure and deterministic: the same key and
 * node set always yield the same ordering, independent of input order.
 *
 * <p>score(key, node) = first 8 bytes of SHA-256(UTF-8(key + "|" + nodeId)), read as an unsigned
 * big-endian 64-bit integer. Higher scores rank first; ties break by node ID ascending.
 */
public final class RendezvousPlacement {

    private RendezvousPlacement() {}

    public static String chunkKey(java.util.UUID objectId, int chunkIndex) {
        return objectId + ":" + chunkIndex;
    }

    /** All given nodes, best first. Callers take a prefix; duplicates in the input are removed. */
    public static List<String> rank(String key, Collection<String> nodeIds) {
        return nodeIds.stream()
                .distinct()
                .sorted(Comparator.<String>comparingLong(n -> score(key, n))
                        .reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** Returns the score with its sign bit flipped so signed comparison equals unsigned comparison. */
    static long score(String key, String nodeId) {
        byte[] digest = Sha256.newDigest().digest((key + "|" + nodeId).getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest, 0, 8).getLong() ^ Long.MIN_VALUE;
    }
}
