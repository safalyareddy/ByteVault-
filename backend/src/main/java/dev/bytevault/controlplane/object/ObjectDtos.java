package dev.bytevault.controlplane.object;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ObjectDtos {

    private ObjectDtos() {}

    public record ObjectSummary(UUID id, String name, String contentType, long sizeBytes, String status,
                                int replicationFactor, int chunkCount, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt) {}

    public record Page<T>(List<T> items, int page, int size, long totalItems) {}

    public record ReplicaView(String nodeId, String state, String nodeState, OffsetDateTime storedAt,
                              OffsetDateTime lastVerifiedAt, String lastError) {}

    public record ChunkView(int index, UUID chunkId, long byteOffset, int byteSize, String sha256, String status,
                            int verifiedReplicas, List<ReplicaView> replicas) {}

    public record ObjectDetail(ObjectSummary object, List<ChunkView> chunks) {}
}
