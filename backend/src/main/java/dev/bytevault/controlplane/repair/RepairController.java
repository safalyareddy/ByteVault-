package dev.bytevault.controlplane.repair;

import dev.bytevault.controlplane.audit.AuditWriter;
import dev.bytevault.controlplane.web.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RepairController {

    private static final Set<String> STATES = Set.of("PENDING", "CLAIMED", "COPYING", "VERIFYING", "COMMITTED",
            "FAILED", "ABANDONED");

    private final JdbcClient jdbc;
    private final Scrubber scrubber;
    private final InventoryReconciler inventory;
    private final dev.bytevault.controlplane.security.CurrentUser guard;
    private final AuditWriter audit;

    public RepairController(JdbcClient jdbc, Scrubber scrubber, dev.bytevault.controlplane.security.CurrentUser guard,
                            AuditWriter audit, InventoryReconciler inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.scrubber = scrubber;
        this.guard = guard;
        this.audit = audit;
    }

    public record RepairView(UUID id, UUID chunkId, UUID objectId, Integer chunkIndex, String reason, String state,
                             String sourceNodeId, String targetNodeId, int attempts, int maxAttempts, String lastError,
                             OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime completedAt,
                             OffsetDateTime nextAttemptAt) {}

    @GetMapping("/repairs")
    public List<RepairView> repairs(@RequestParam(required = false) String state,
                                    @RequestParam(defaultValue = "50") int limit) {
        if (state != null && !STATES.contains(state)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "unknown state " + state);
        }
        return jdbc.sql("""
                SELECT j.id, j.chunk_id, c.object_id, c.chunk_index, j.reason, j.state, j.source_node_id,
                       j.target_node_id, j.attempts, j.max_attempts, j.last_error, j.created_at, j.started_at,
                       j.completed_at, j.next_attempt_at
                  FROM repair_jobs j JOIN chunks c ON c.id = j.chunk_id
                 WHERE (CAST(:s AS text) IS NULL OR j.state = :s)
                 ORDER BY j.created_at DESC LIMIT :lim
                """).param("s", state).param("lim", Math.min(Math.max(limit, 1), 500))
                .query(RepairView.class).list();
    }

    /**
     * Node inventory vs metadata (after a database restore). Dry run unless {@code apply=true}.
     * ADMIN only, audited.
     */
    @PostMapping("/admin/reconcile")
    public InventoryReconciler.Report reconcile(@RequestParam(defaultValue = "false") boolean apply) {
        return inventory.run(apply, guard.requireAdmin());
    }

    /** Runs one scrub pass now (admin, audited). Useful for demos; the scheduler runs it anyway. */
    @PostMapping("/admin/scrub")
    public Scrubber.ScrubSummary scrubNow(@RequestParam(defaultValue = "1000") int limit) {
        String actor = guard.requireAdmin();
        Scrubber.ScrubSummary summary = scrubber.scrub(Math.min(Math.max(limit, 1), 10_000));
        audit.record(actor, "SCRUB_RUN", "CLUSTER", "scrub", Map.of("limit", limit, "checked", summary.checked(),
                "corrupt", summary.corrupt(), "missing", summary.missing()));
        return summary;
    }
}
