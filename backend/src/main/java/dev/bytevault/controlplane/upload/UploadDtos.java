package dev.bytevault.controlplane.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class UploadDtos {

    private UploadDtos() {}

    public record CreateUploadRequest(
            @NotBlank @Size(max = 1024) String name,
            @NotNull @PositiveOrZero Long sizeBytes,
            @Size(max = 255) String contentType) {}

    public record UploadView(
            UUID uploadId, UUID objectId, String status, String objectStatus, long sizeBytes, int chunkSizeBytes,
            int chunkCount, int replicationFactor, OffsetDateTime expiresAt, Integer storedChunks,
            List<Integer> missingChunkIndexes, boolean replayed) {}

    public record ReplicaWrite(String nodeId, String state, String error) {}

    public record ChunkUploadResult(int index, String sha256, boolean stored, int verifiedReplicas,
                                    int requiredReplicas, List<ReplicaWrite> replicas) {}

    public record CompleteResult(UUID objectId, String status, long sizeBytes, int chunkCount, int replicationFactor) {}
}
