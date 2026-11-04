package dev.bytevault.controlplane.repair;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RepairJob(UUID id, UUID chunkId, String sourceNodeId, String targetNodeId, String reason, String state,
                        int attempts, int maxAttempts, String lastError, String leaseOwner, OffsetDateTime leaseUntil,
                        OffsetDateTime nextAttemptAt, OffsetDateTime createdAt, OffsetDateTime startedAt,
                        OffsetDateTime completedAt) {

    static final String COLUMNS = "id, chunk_id, source_node_id, target_node_id, reason, state, attempts, max_attempts, "
            + "last_error, lease_owner, lease_until, next_attempt_at, created_at, started_at, completed_at";
}
