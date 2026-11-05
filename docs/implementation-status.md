# Implementation status

This file records evidence, not intent. A requirement is **Implemented** only
when a command below has actually been run and passed. Statuses:
**Implemented**, **Implemented, unverified** (code exists, but its gate
hasn't run), **Partial**, **Not started**, **Deferred**.

Last updated: 2026-11-04.

## Current phase

**Phase 0:** complete.
**Phase 1:** **gate met** on 2026-11-04. Startup from empty volumes is clean,
every health check passes, and all 25 automated tests pass, including 8
integration tests against real PostgreSQL and Kafka.
**Phase 2:** **gate met** on 2026-11-04. An end-to-end upload and download is
byte-for-byte identical, both in the automated `StorageIT` against 3 real node
containers and with a 25 MiB file on the live Compose stack.
**Phase 3:** **gate met** on 2026-11-04. All of these are covered by
automated tests, and a live run on the Compose stack confirmed them:
- signed push heartbeats
- JOINING admission with an authenticated probe
- a heartbeat-age failure detector
- capacity-aware rendezvous placement
- RF=3 with every replica verified on distinct nodes
**Phase 4:** **gate met** on 2026-11-04. All of these are covered by
automated tests against a real broker, and a live Kafka outage confirmed them
on the Compose stack:
- outbox publisher
- idempotent consumers
- bounded retries with jitter
- DLQ
- guarded, audited replay
**Phase 5:** **gate met** on 2026-11-04. All of these are covered by
automated tests against 4 real node containers and were demonstrated live on
the Compose stack:
- OFFLINE detection
- lease-based repair from verified sources
- scrubbing
- rejoin reconciliation
- drain
**Phase 6:** **gate met** on 2026-11-04 for the security and operations scope:
- authentication with USER/ADMIN roles
- hashed, revocable tokens
- rate and size limits
- CORS and security headers
- backup with a verified restore
- inventory reconciliation

The full restore drill against the live database (which is destructive) hasn't
been run yet. Restore was verified non-destructively instead, into a scratch
database.
**Dashboard (frontend):** **gate met** on 2026-11-05. Every screen was
checked in the browser against the live API after the user signed in. That
included real uploads, deletes, DLQ replay, scrub and reconcile, plus a node
stop, a Kafka stop and a control-plane stop with the console open. These
checks found two failure-detector defects, which are now fixed (note 4, items
g and h).

**Final phase (hardening and delivery):** done on 2026-11-05:
- OpenAPI published
- smoke test and benchmark harness
- measured benchmarks (3 runs each)
- fresh-clone run-through
- demo script
- data model and testing guides

Remaining gaps are listed under "Not done" below.

### Not done (honest list)
- **Destructive restore drill on the live database.** Restore was verified
  into a scratch database; reconciliation is covered by `SelfHealingIT`.
- **Lint/format tooling** (Spotless, ESLint): deferred.
- **Grafana dashboards.** The monitoring profile starts Prometheus and Grafana,
  but no dashboards are provisioned, and that profile hasn't been run.
- **CI on GitHub.** There's no remote; the same commands pass locally.
- **No account management:** no password change, MFA, or user
  disable/delete endpoint.
- **No degraded override for drain.**
- **Load testing** beyond the benchmark harness. Rate limits and transfer
  permits aren't tested under load.
- **Cold-cache, multi-host and large-object benchmarks.**
- **Browser checks not done:** drain from the UI, the USER-role view, and
  create-user from the UI. All three are covered by automated tests.

## Requirements

| ID | Requirement | Status | Location | Verification command | Last run | Evidence |
|---|---|---|---|---|---|---|
| P0-1 | Reconnaissance, plan, ADRs | Implemented | `docs/adr/`, `docs/architecture.md` | review | 2026-11-04 | docs committed to repo |
| P0-2 | State machines, Kafka catalogue, node protocol, threat model | Implemented | `docs/*.md` | review | 2026-11-04 | - |
| P1-1 | Maven multi-module build targeting Java 21 | Implemented | `pom.xml`, `backend/`, `storage-node/` | `mvn -B -ntp package` | 2026-11-04 | BUILD SUCCESS on JDK 25.0.2 with `release=21` (see note 1) |
| P1-2 | Control-plane config validation (chunk size, RF, heartbeat ordering) | Implemented | `backend/.../config/BytevaultProperties.java` | `mvn test` | 2026-11-04 | `BytevaultPropertiesTest` 3/3 pass |
| P1-3 | Correlation IDs (header + MDC, log-injection safe) | Implemented | `backend/.../web/CorrelationIdFilter.java` | `mvn test` | 2026-11-04 | `CorrelationIdFilterTest` 3/3 pass |
| P1-4 | Error envelope | Implemented | `backend/.../web/GlobalExceptionHandler.java` | `mvn verify` | 2026-11-04 | `FoundationIT.unknownEndpointReturnsErrorEnvelope` pass |
| P1-5 | `GET /api/v1/health` (liveness + readiness incl. db, kafka; no secret leakage) | Implemented | `backend/.../health/` | `mvn verify`; `scripts/status.sh` | 2026-11-04 | unit 2/2 + IT pass; on the live stack: 200/UP; with Kafka stopped 503 and `kafka: DOWN`; recovered about 2 s after Kafka restart; body contains no hosts or credentials |
| P1-6 | Flyway baseline schema with invariants (distinct-node replicas, verified-has-checksum, one active repair per chunk, append-only audit) | Implemented | `backend/src/main/resources/db/migration/V1__baseline_schema.sql` | `mvn verify` | 2026-11-04 | `FoundationIT` 5 schema tests pass; Compose Postgres shows V1 success, 10 tables |
| P1-7 | Kafka topics declared explicitly, retried until created | Implemented | `backend/.../config/KafkaTopicsConfig.java`, `TopicInitializer.java` | `mvn verify`; `kafka-topics.sh --describe` | 2026-11-04 | `allTopicsDeclared` pass; live broker lists all 7 topics (3 partitions, RF 1) |
| P1-8 | Storage-node identity, safe data dir (0700, symlink rejected), measured capacity | Implemented | `storage-node/.../storage/DataDirectory.java` | `mvn test` + manual jar run | 2026-11-04 | `DataDirectoryTest` 4/4, `NodeHealthControllerTest` 1/1; jar on host returned real `/internal/v1/health` and actuator liveness 200 |
| P1-9 | Node refuses weak or missing secret without leaking it | Implemented | `storage-node/.../config/NodeProperties.java` | `mvn test` + manual jar run | 2026-11-04 | `NodePropertiesTest` 4/4; startup with a short secret exits 1 and the value appears 0 times in the log |
| P1-10 | Compose topology (Postgres, Kafka KRaft, backend, 3 nodes, monitoring profile) | Implemented | `infra/compose/docker-compose.yml` | `scripts/up.sh`, `scripts/status.sh` | 2026-11-04 | all 6 containers healthy; 3 nodes with distinct IDs and volumes; non-root, data dir 0700; measured memory: backend 189 MiB, Kafka 323 MiB, Postgres 42 MiB, nodes about 133 MiB each. Monitoring profile config-validated only (not started) |
| P1-11 | Non-destructive stop vs. guarded destructive reset | Implemented | `scripts/down.sh`, `scripts/reset-volumes.sh` | `bash -n` | 2026-11-04 | syntax-checked only; reset requires typed confirmation |
| P1-12 | React shell showing live health with loading/offline/degraded states | Implemented | `frontend/src/` | `npm run build` + browser check | 2026-11-04 | checked in the browser: UP view; degraded view (Kafka stopped); offline view (backend stopped) |
| P1-13 | CI (Java verify with Testcontainers, frontend build, compose config) | Implemented, unverified | `.github/workflows/ci.yml` | GitHub Actions | not run | no remote configured; the same commands pass locally |
| P1-14 | Lint/format tooling (Spotless/ESLint) | Deferred | - | - | - | deferred to keep the first downloads small; tracked for Phase 7 |
| P2-1 | Node protocol v1: signed PUT/GET/DELETE, canonical chunk IDs, timestamp window, node-ID binding | Implemented | `node-protocol/`, `storage-node/.../web/NodeAuthFilter.java`, `ChunkController.java` | `mvn verify` | 2026-11-04 | `RequestSignerTest` 6/6, `ChunkControllerTest` 4/4 (unsigned → 401, wrong node → 403, wrong secret → 401) |
| P2-2 | Durable node write: temp file, streaming SHA-256, length check, fsync, sidecar, atomic rename, dir fsync | Implemented | `storage-node/.../storage/ChunkStore.java` | `mvn verify` | 2026-11-04 | `ChunkStoreTest` 6/6: round trip, mismatch stores nothing, idempotent retry, conflict, 8 concurrent writers → 1 CREATED, idempotent delete |
| P2-3 | Safe paths: only UUID-derived, containment check, no client filenames | Implemented | `ChunkStore`, `NodeProtocol.parseChunkId` | `mvn verify` | 2026-11-04 | non-canonical and traversal IDs rejected (unit) and return 400 (controller) |
| P2-4 | Upload API: idempotent session, streamed chunk PUT, client checksum, exact length, complete | Implemented | `backend/.../upload/` | `mvn verify` (`StorageIT`) | 2026-11-04 | same key → same session (200, `replayed`); key reuse with other params → 422; wrong SHA → 422; wrong length → 400; out-of-range index → 404; different content for a stored index → 409 |
| P2-5 | Download: per-chunk verify before sending, fallback to another replica, Content-Length | Implemented | `backend/.../object/DownloadService.java` | `mvn verify` (`StorageIT`) | 2026-11-04 | byte-identical download of a 4-chunk object; corrupted copy on node-1 is never served, the download still matches, and the replica is marked CORRUPT |
| P2-6 | Tombstone-first async delete, retried until bytes are gone from every node | Implemented | `backend/.../object/DeletionService.java` | `mvn verify` (`StorageIT`) | 2026-11-04 | DELETE twice → 202; object reaches DELETED; `sha256sum` on all 3 node disks finds no chunk files |
| P2-7 | Object list/detail APIs with chunk + replica state | Implemented | `backend/.../object/ObjectController.java` | `mvn verify`; `scripts/bv.sh show` | 2026-11-04 | used by `StorageIT` and the live demo |
| P2-8 | Backpressure: bounded concurrent chunk transfers (spool disk ≤ N×chunk), bounded replica I/O pool | Implemented, unverified | `TransferPermits.java`, `ExecutorsConfig.java` | - | - | not load-tested yet (Phase 7 benchmark) |
| P2-9 | Metrics: `bv_upload_bytes_total`, `bv_upload_failures_total`, `bv_chunk_write_seconds`, `bv_checksum_failures_total`, `bv_chunk_transfers_in_flight` | Implemented, unverified | upload/download services, `ChunkStore` | `curl :8090/actuator/prometheus` | - | not yet checked on the live stack |
| P3-1 | Deterministic rendezvous placement, distinct nodes | Implemented | `backend/.../placement/RendezvousPlacement.java` | `mvn test` | 2026-11-04 | 5/5, including expected values computed independently in Python, minimal movement when a node is removed, and even spread |
| P3-2 | RF=3 with all replicas VERIFIED before COMPLETE (ADR-0005) | Implemented | `UploadService` | `mvn verify` (`StorageIT`) | 2026-11-04 | each chunk on node-1/2/3, checksum confirmed with `sha256sum` on each node's own disk |
| P3-3 | Insufficient healthy nodes never yield COMPLETE | Implemented | `UploadService`, `NodeProbe` | `mvn verify` (`StorageIT` order 7) | 2026-11-04 | node-3 paused → SUSPECT (audited) → chunk PUT 503 `INSUFFICIENT_HEALTHY_NODES` → complete 409 |
| P3-4 | Node inventory API with real heartbeat data | Implemented | `backend/.../node/NodeController.java` | `mvn verify`; `scripts/bv.sh nodes` | 2026-11-04 | heartbeat age 0–3 s; no secrets in the response |
| P3-5 | Signed push heartbeats; body hash signed; unknown/forged/tampered/cross-node replays → 401 | Implemented | `storage-node/.../heartbeat/HeartbeatSender.java`, `backend/.../node/HeartbeatController.java` | `mvn verify` | 2026-11-04 | `HeartbeatSenderTest` 2/2; `StorageIT.forgedOrTamperedHeartbeatsAreRejected` (unsigned, wrong secret, altered body, node-1 signature on node-2 path) |
| P3-6 | Admission: N consecutive UP heartbeats + signed probe of the configured endpoint (ADR-0010) | Implemented | `NodeLifecycleService`, `NodeClient.admissionProbe` | `mvn verify` | 2026-11-04 | node-4 (wrong secret) is never registered; node-5 (valid secret, unreachable endpoint) stays JOINING; 0 admissions audited for either |
| P3-7 | Node lifecycle rules enforced in code; invalid transitions rejected | Implemented | `backend/.../node/NodeLifecycle.java`, `NodeRepository.transition` | `mvn test` | 2026-11-04 | `NodeLifecycleTest` 5/5 checks the full 5×5 transition table against the doc |
| P3-8 | Failure detector: HEALTHY → SUSPECT after `suspect-after` without a heartbeat; SUSPECT → HEALTHY on resume | Implemented | `backend/.../node/FailureDetector.java` | `mvn verify`; live pause | 2026-11-04 | IT: node-3 paused → SUSPECT → unpaused → HEALTHY (both audited). Live: node-2 SUSPECT after 16 s (threshold 15 s), HEALTHY on the next heartbeat after unpause |
| P3-9 | Capacity-aware placement (chunk + configurable reserve), never silently fewer targets | Implemented | `backend/.../placement/PlacementPolicy.java` | `mvn test` | 2026-11-04 | `PlacementPolicyTest` 5/5, including the exact reserve boundary, non-HEALTHY exclusion and unsatisfiable placement. Capacity reflects the Docker VM disk (note 2) |
| P3-10 | Node metrics: `bv_node_heartbeat_age_seconds`, `bv_node_state`, `bv_node_capacity_bytes`, `bv_node_usable_bytes`, `bv_heartbeats_rejected_total` | Implemented | `FailureDetector`, `HeartbeatController` | `curl :8090/actuator/prometheus` | 2026-11-04 | live: age 0, `bv_node_state{state="HEALTHY"}` = 1 for all 3 nodes |
| P3-11 | OFFLINE transition + replica LOST marking | Implemented | `FailureDetector`, `NodeLifecycleService.markOffline` | `mvn verify` (`SelfHealingIT`); live | 2026-11-04 | live: node-2 stopped → SUSPECT +14 s → OFFLINE +45 s, 13 replicas LOST, objects DEGRADED, all in one transaction |
| P5-1 | Under-replication planner: one active job per chunk, reason classified, 10-min cool-off after ABANDONED | Implemented | `backend/.../repair/RepairPlanner.java` | `mvn verify` | 2026-11-04 | partial unique index + `ON CONFLICT DO NOTHING`; reasons NODE_OFFLINE/CORRUPTION/DRAIN asserted in IT |
| P5-2 | Repair worker: CAS lease claim, verified source read, target write, target re-hash from disk, then transactional commit; no transaction across transfers; restartable | Implemented | `backend/.../repair/RepairWorker.java` | `mvn verify`; live | 2026-11-04 | IT: every repaired copy checked with `sha256sum` on the target disk. Live: 13 jobs, all COMMITTED on the first attempt, source ≠ target |
| P5-3 | Old copies retired only after the replacement is VERIFIED and committed | Implemented | `ReplicationStatus.retireSuperseded` | `mvn verify` | 2026-11-04 | retire runs inside the commit transaction and only when active VERIFIED ≥ RF |
| P5-4 | Bounded concurrency: global max (2) + one active job per target node | Implemented | `RepairWorker` | - | - | not load-tested |
| P5-5 | No target available → job waits without burning attempts; RF never lowered | Implemented | `RepairWorker.waitForCapacity` | `mvn verify` | 2026-11-04 | 2 of 4 nodes paused → chunk repaired to 2 copies, then waits; object DEGRADED but downloadable; 0 ABANDONED; restored to 3 when the nodes return |
| P5-6 | Checksum scrubber: least recently verified first, batch-limited, paced; admin "scrub now" (audited) | Implemented | `backend/.../repair/Scrubber.java`, `POST /api/v1/admin/scrub` | `mvn verify`; live | 2026-11-04 | live: 1 flipped byte → scrub 39 checked / 1 corrupt → repaired in place within 4 s; all 3 disks match metadata; download SHA-256 matches |
| P5-7 | Rejoin reconciliation: LOST copies re-verified if still needed, else deleted; never > RF | Implemented | `backend/.../repair/RejoinReconciler.java` | `mvn verify`; live | 2026-11-04 | live: node-2 restarted → re-admitted in 13 s → 13 stale copies DELETED, 0 chunk files left on disk, 0 chunks over RF |
| P5-8 | Graceful drain (admin, audited): no new placements, copies moved before removal, `safeToRemove`, cancel | Implemented | `backend/.../node/DrainController.java` | `mvn verify` | 2026-11-04 | IT: 401 without token; new uploads avoid the draining node; safeToRemove only after its copies are moved and deleted from its disk; object stays COMPLETE throughout. No degraded override in v1 |
| P5-9 | Guarded fault-injection scripts | Implemented | `scripts/fault/` | live | 2026-11-04 | `stop-node.sh` refuses without `--yes` (observed); `corrupt-chunk.sh` refuses unless the object name starts with `disposable-` and the chunk ID is typed back (wrong answer → "Aborted; nothing changed", observed) |
| P5-10 | Repair metrics: `bv_under_replicated_chunks`, `bv_repair_jobs_total{result}`, `bv_repair_duration_seconds`, `bv_repair_jobs_in_flight`, `bv_checksum_failures_total{path}`, `bv_scrub_replicas_checked_total` | Implemented | repair package | `mvn verify` (registry assertions) | 2026-11-04 | |
| P6-1 | Accounts: BCrypt(12) passwords of 12–72 bytes; bootstrap ADMIN from env (first start only); ADMIN-only user creation, audited | Implemented | `backend/.../security/UserService.java`, `V3__users_and_tokens.sql` | `mvn verify` (`SecurityIT`); live | 2026-11-04 | weak password / bad username / duplicate → 400/400/409; stored hash starts `$2`; live: admin created from `.env`, password appears 0 times in the logs |
| P6-2 | Opaque 256-bit bearer tokens, stored as SHA-256 only, 12 h expiry, revoked at logout | Implemented | `UserService`, `BearerTokenFilter` | `mvn verify` | 2026-11-04 | token not findable in the DB; logout → 401; expired → 401; garbage → 401 |
| P6-3 | Roles: USER = own uploads/objects; ADMIN = also nodes, repairs, events, stats, drain, scrub, reconcile, DLQ, users. JSON 401/403 envelopes | Implemented | `SecurityConfig` | `mvn verify` | 2026-11-04 | 7 endpoints → 401 without a token; USER → 403 on 5 admin reads, drain, scrub and user creation; live: 401/401/200 for objects/nodes/health |
| P6-4 | Owner isolation without leaking existence | Implemented | owner-scoped queries | `mvn verify` | 2026-11-04 | alice gets 404 for get/download/delete of admin's object, which is absent from her list and still COMPLETE afterwards |
| P6-5 | Login abuse limits: 10/min per IP+username, 50/min per IP; failures audited; no username enumeration | Implemented | `RateLimiter`, `AuthController`, `RequestLimitsFilter` | `mvn verify` | 2026-11-04 | 11th wrong attempt → 429; unknown user and wrong password give the same `INVALID_CREDENTIALS`; `RateLimiterTest` 2/2 (refill, eviction) |
| P6-6 | API rate limit per user (1200/min, per IP when anonymous) | Implemented, unverified | `RequestLimitsFilter` | - | - | covered by unit tests of the bucket only; not load-tested |
| P6-7 | 64 KiB cap on non-chunk bodies, enforced on Content-Length **and** while reading (so chunked encoding can't bypass it) | Implemented | `RequestLimitsFilter`, `LimitedBodyRequest` | `mvn verify` | 2026-11-04 | both variants → 413 `REQUEST_TOO_LARGE` |
| P6-8 | CORS allow-list; CSP `default-src 'none'`, `X-Frame-Options: DENY`, `nosniff`, `no-store`; stateless (no CSRF surface) | Implemented | `SecurityConfig` | `mvn verify` | 2026-11-04 | allowed origin echoed; foreign origin preflight → 403 |
| P6-9 | Metadata backup | Implemented | `scripts/backup-db.sh` | live | 2026-11-04 | 64,584-byte dump, mode 600, git-ignored; restored into a scratch DB with identical counts (4 objects, 52 replicas, 1 user, Flyway v3), then dropped |
| P6-10 | Guarded restore | Implemented, unverified | `scripts/restore-db.sh` | - | - | **the full drill against the live DB hasn't been run** (destructive; awaiting approval) |
| P6-11 | Inventory reconciliation: orphans (deleted with apply) and missing files (marked LOST, then repaired); dry run by default; audited | Implemented | `backend/.../repair/InventoryReconciler.java`, node `GET /internal/v1/chunks` | `mvn verify`; live | 2026-11-04 | IT: an injected orphan and a deleted file are reported; dry run changed nothing; apply deleted the orphan and repair restored the copy; download hash matches. Live dry run: 13/13/13 files match metadata on 3 nodes, 0 orphans, 0 missing |
| P6-12 | Operations runbook | Implemented | `docs/operations-runbook.md` | review | 2026-11-04 | |
| P7F-1 | Operations console: login, overview, nodes (drain/cancel), objects (chunked upload with progress and in-browser SHA-256, detail with per-node replica states, download, delete), repairs, event timeline (correlation filter, lag), admin (DLQ replay, scrub, reconcile, create user) | Implemented | `frontend/src/` | typecheck/test/build + browser | 2026-11-05 | wrong password → error. Overview numbers equal `/api/v1/overview` field by field. A 9,500,000-byte upload through the UI showed per-chunk progress, landed 3/3 VERIFIED on distinct nodes, and its download SHA-256 equals the hash computed in the browser before upload. Delete via the typed confirmation → DELETING → ObjectDeleted in about 4 s. Correlation filter narrows to one event. Scrub: 39/39 intact. Reconcile dry run: 0 orphans, 0 missing. DLQ replay recorded REPLAYED by `admin`. **Not checked in the browser:** drain from the UI (covered by `SelfHealingIT`), the USER-role view (covered by `SecurityIT`), create-user from the UI |
| P7F-2 | No hardcoded live values; loading / empty / error / offline / degraded / stale states | Implemented | `hooks/usePoll.ts`, `components/ui.tsx`, `components/Layout.tsx` | browser with live faults | 2026-11-05 | node-2 stopped: SUSPECT at 19 s, OFFLINE with red/amber cards mid-repair, then restored. Kafka stopped: DOWN badge, amber "kafka not ready" banner, outbox backlog 2. Control plane stopped: OFFLINE badge, red banner, every panel "Stale — last updated …" with last data kept |
| P7F-3 | `GET /api/v1/overview` (ADMIN) | Implemented | `backend/.../events/OverviewController.java` | `mvn verify` (`SecurityIT`) | 2026-11-04 | USER → 403; ADMIN body has nodes, objects and events blocks |
| P8-1 | OpenAPI 3 description of the public API | Implemented | `OpenApiConfig`, springdoc 2.8.9, `docs/openapi.json` | `curl :8090/v3/api-docs` | 2026-11-05 | 23 paths, bearer security scheme; node-internal protocol documented separately |
| P8-2 | Smoke test (login, upload, replica check, download hash, delete) without exposing credentials | Implemented | `scripts/bvtool.py smoke` | run | 2026-11-05 | PASSED on the main stack and on the fresh clone |
| P8-3 | Reproducible benchmarks: throughput, p50/p95 latency, event lag, CPU/RAM, repair duration; ≥ 3 runs; raw output | Implemented | `scripts/bvtool.py bench`, `bench-repair`; `docs/benchmark-report.md` | run | 2026-11-05 | 12 throughput runs and 3 repair runs, every download hash-verified; high variance reported, not hidden |
| P8-4 | Fresh-clone reproducibility | Implemented | README quick start | clone + `gen-env` / `build` / `up` / `status` / smoke under `COMPOSE_PROJECT_NAME=bvfresh` | 2026-11-05 | 61 s from clone to SMOKE TEST PASSED on new volumes with new secrets. **Caveat:** Maven, npm and Docker images were already cached on this machine, so a new machine needs extra download time |
| P8-5 | Demo script, data model (ERD), testing guide | Implemented | `docs/demo-script.md`, `docs/data-model.md`, `docs/testing.md` | review | 2026-11-05 | |
| P2-10 | Outbox rows written in the same transaction as state changes | Implemented | `backend/.../outbox/OutboxWriter.java` | `mvn verify` | 2026-11-04 | refuses to run outside a transaction; rows published by P4-1 |
| P4-1 | Outbox publisher: SKIP LOCKED claim, ordered send with aggregate key, acked-prefix marking, global backoff with jitter | Implemented | `backend/.../outbox/OutboxPublisher.java` | `mvn verify` (`KafkaReliabilityIT`) | 2026-11-04 | published_at set after the ack; outage test below. Live: 12 events left over from Phases 2–3 were published on deploy, and the projection matched the database |
| P4-2 | Idempotent consumers: `processed_events` in the effect transaction, aggregate-version check | Implemented | `backend/.../events/IdempotentProcessor.java`, `ObjectStatsConsumer`, `TimelineConsumer` | `mvn verify` | 2026-11-04 | `duplicateDeliveryProducesOneLogicalEffect` (same message sent twice → +777 bytes once, 1 timeline row) |
| P4-3 | Crash between effect commit and offset ack is safe | Implemented | `IdempotentProcessor`, `AckMode.RECORD` | `mvn verify` | 2026-11-04 | `crashAfterCommitBeforeAckIsRedeliveredAndSkipped`: injected crash → redelivery observed → applied once, no DLQ |
| P4-4 | Bounded exponential retry with ±20% jitter, then DLQ with topic/key/event ID/reason/attempts/timestamp/correlation ID/group | Implemented | `JitteredBackOff`, `KafkaConsumerConfig`, `DlqRecorder` | `mvn verify` | 2026-11-04 | `JitteredBackOffTest` 22/22; 2 transient failures → 3 calls, applied once; permanent failure → exactly 5 attempts → DLQ row with every field asserted |
| P4-5 | Malformed messages skip retries | Implemented | `InvalidEventException` (not retryable) | `mvn verify` | 2026-11-04 | poison message → one DLQ entry per consumer group, attempts = 1 |
| P4-6 | Guarded replay keeps the event ID, CAS-claimed, audited with operator/reason/attempt | Implemented | `backend/.../admin/DlqAdminController.java`, `AdminGuard.java` | `mvn verify`; live curl | 2026-11-04 | IT: no/wrong token 401, missing reason 400, replay → applied once, timeline deduplicated it, second replay 409, audit row asserted. Live: 401/401/200; token in logs 0 times |
| P4-7 | Kafka outage: outbox accumulates, drains on recovery | Implemented | `OutboxPublisher` | `mvn verify`; live `docker compose stop kafka` | 2026-11-04 | IT: broker paused → 5 events held → unpaused → drained, applied once. Live: upload succeeded during the outage, backlog 4, health 503, stats `consistent:false`; Kafka started → drained in 4 s → `consistent:true`; download SHA-256 matches |
| P4-8 | Stopped consumer catches up without loss or duplication | Implemented | listener containers | `mvn verify` | 2026-11-04 | stats container stopped, 5 events published, restarted → exactly +1500 bytes |
| P4-9 | Event timeline and stats APIs (projection next to the authoritative counts) | Implemented | `backend/.../events/EventQueryController.java` | live curl | 2026-11-04 | `GET /api/v1/events`, `GET /api/v1/stats` |
| P4-10 | Outbox/consumer metrics: backlog, oldest age, published, failures, consumed (applied/duplicate), retries, dead-lettered | Implemented | publisher, processor, error handler | `curl :8090/actuator/prometheus` | 2026-11-04 | live values seen. Note: `bv_outbox_publish_failures_total` only increments once a send times out (≤15 s), so it can read 0 early in an outage |
| P4-11 | Consumer lag metric | Partial | Kafka client metrics through Micrometer | - | - | relies on the standard `kafka_consumer_fetch_manager_records_lag_max`; not yet checked on the live stack |
| P4-* | Outbox, consumers, retries, DLQ, replay | Not started | | | | |
| P5-* | Heartbeats, failure detection, repair, scrubber, drain | Not started | | | | |
| P6-* | Auth (USER/ADMIN), node auth enforcement, rate limits | Not started | | | | |
| P6F-* | Operations dashboard screens | Not started | | | | |
| P7-* | Fault tests, benchmarks, backup/restore, runbook, demo | Not started | | | | |

## Test run log

| Date | Command | Result |
|---|---|---|
| 2026-11-04 | `mvn -B -ntp package` (unit tests) | 17 tests, 0 failures: backend 8 (Properties 3, CorrelationId 3, ApiHealth 2), storage-node 9 (NodeProperties 4, DataDirectory 4, NodeHealth 1) |
| 2026-11-04 | `docker compose -f infra/compose/docker-compose.yml --env-file .env.example [--profile monitoring] config --quiet` | valid |
| 2026-11-04 | `npm run build` (frontend) | pass |
| 2026-11-04 | `mvn -B -ntp verify` | 25 tests, 0 failures: 17 unit + 8 `FoundationIT` (Postgres 16.9, Kafka 3.9.1 via Testcontainers) |
| 2026-11-04 | `scripts/gen-env.sh && scripts/up.sh` from empty volumes | all 6 services healthy; `/api/v1/health` UP |
| 2026-11-04 | manual: stop Kafka, restart Kafka, stop backend | health went 503/kafka DOWN, then UP again about 2 s after restart; console showed degraded, then offline |
| 2026-11-04 | scan of every container log for the 4 values in `.env` | 0 occurrences |
| 2026-11-04 | `mvn -B -ntp verify` (Phase 2) | **58 tests, 0 failures, 0 skipped**: node-protocol 6, storage-node 19, backend unit 17, backend IT 16 (`FoundationIT` 8, `StorageIT` 8, with 3 real node containers) |
| 2026-11-04 | `mvn -B -ntp verify` (Phase 3) | **71 tests, 0 failures, 0 skipped**: node-protocol 6, storage-node 21, backend unit 27, backend IT 17 (`StorageIT` 9 with 5 node containers, 2 of them deliberately misconfigured) |
| 2026-11-04 | `mvn -B -ntp verify` (Phase 4) | **101 tests, 0 failures, 0 skipped**: node-protocol 6, storage-node 21, backend unit 49, backend IT 25 (`FoundationIT` 8, `StorageIT` 9, `KafkaReliabilityIT` 8) |
| 2026-11-05 | `python3 -I scripts/bvtool.py bench` and `bench-repair` | see `docs/benchmark-report.md`; all 15 runs verified |
| 2026-11-05 | fresh clone (`bvfresh`): README quick start + smoke | PASSED in 61 s; the project and its volumes were removed afterwards |
| 2026-11-05 | live: 70 s `docker compose pause backend`; then 80 s `stop backend` + `start` | 0 node transitions, 0 repair jobs, 0 LOST, all nodes HEALTHY (before the fix: all 4 nodes OFFLINE in both cases) |
| 2026-11-04 | `mvn -B -ntp verify` (with overview API) | 122 tests, 0 failures |
| 2026-11-04 | `npm run typecheck && npm test && npm run build` (frontend) | clean; 6/6 tests; build OK |
| 2026-11-04 | `mvn -B -ntp verify` (Phase 6) | **122 tests, 0 failures, 0 skipped**: node-protocol 6, storage-node 23, backend unit 53, backend IT 40 (`FoundationIT` 8, `StorageIT` 9, `KafkaReliabilityIT` 8, `SelfHealingIT` 6, `SecurityIT` 9) |
| 2026-11-04 | `mvn -B -ntp verify` (Phase 5) | **114 tests, 0 failures, 0 skipped**: node-protocol 6, storage-node 22, backend unit 50, backend IT 30 (`FoundationIT` 8, `StorageIT` 9, `KafkaReliabilityIT` 8, `SelfHealingIT` 5 with 4 node containers) |
| 2026-11-04 | live (`--profile spare`, 4 nodes): `fault/stop-node.sh node-2 --yes` → `start-node.sh node-2` → `fault/corrupt-chunk.sh` + admin scrub | OFFLINE at +45 s, all copies repaired onto node-4 by +53 s; rejoin deleted 13 stale copies; corruption repaired in place in 4 s; download SHA-256 `931a77c8…a3a1` unchanged throughout |
| 2026-11-04 | live: `docker compose stop kafka`, upload 6 MB, `start kafka` | upload OK during the outage; backlog 4 → 0 within 4 s of restart; projection consistent; SHA-256 matches |
| 2026-11-04 | live: `docker compose pause node-2`, upload, unpause, upload | SUSPECT after 16 s; upload refused with 503 `INSUFFICIENT_HEALTHY_NODES`; HEALTHY on resume; next 1 MB upload round-trips with matching SHA-256 |
| 2026-11-04 | live stack: `scripts/bv.sh upload` of a 25 MiB random file → `download` → `shasum -a 256` | 7 chunks, each VERIFIED on node-1/2/3; downloaded sha256 `044abd8d…5aa8` equals the original; re-running the upload replays the same object (1 row in `objects`) |

## Notes
1. The JDK 21 installed at `~/jdk-21.0.10.jdk` is incomplete (its `bin/` is
   empty). Local builds used Homebrew OpenJDK 25.0.2 with
   `maven.compiler.release=21`. CI and the container images use Temurin 21.
   To build locally on 21, install one, for example
   `brew install openjdk@21`.
2. Node `capacityBytes`/`usableBytes` are measured honestly, but inside
   containers they describe the **Docker Desktop VM disk** (about 240 GB, sparse).
   That is not the Mac's real free space (8.9 GiB at the time of the run). All
   three nodes share that one disk. Placement and capacity checks (Phase 3)
   must keep a reserve, and the dashboard must label this caveat.
3. Defects found and fixed while running the Phase 1 gate:
   - (a) The storage node's startup error printed a rejected secret. It is now
     checked as a derived property, and a test proves the value is never
     echoed.
   - (b) `spring.kafka.admin.operation-timeout: 10` had no unit and was read as
     milliseconds, which made topic creation time out. It now reads `10s`.
   - (c) With `fail-fast: false`, topics were never created if Kafka was down
     at startup. The new `TopicInitializer` retries every 30 s.
   - (d) The test container bean name clashed with the `kafka` health
     indicator.
   - (e) The console wrongly attributed the dev proxy's 500 to the control
     plane.
4. Defects found and fixed while building Phase 2:
   - (a) `StreamingResponseBody` inside `ResponseEntity<?>` had no converter
     (HTTP 500 on node GET). The node now returns a `Resource`.
   - (b) The CLI didn't recognise an idempotent replay of an already-completed
     upload.
   - (c) Phase 5: a race between two repairs of different chunks of the same
     object, each committing at the same moment. Neither saw the other's
     commit, so the object stayed DEGRADED even though every chunk was
     healthy. `SelfHealingIT` caught it. The fix is to lock the object row
     before recomputing its status, plus a periodic safety sweep, which never
     fired once the lock was in place.
   - (d) `scripts/up.sh` passed `--profile` after `up`, which Compose rejects.
   - (e) Phase 6: the body-size cap originally checked only `Content-Length`,
     so a chunked request body could be read without limit. It's now enforced
     while the body is read.
   - (f) Phase 6: streaming downloads finish on an internal async re-dispatch.
     Spring Security re-checked that dispatch after the response had already
     been sent, which left kept-alive connections broken. The next request on
     such a connection failed with "no bytes". Async and error dispatches are
     now permitted, since the original request was already authorized.
   - (g) Found from the live Overview ("91 repairs done in 1h" on an idle
     cluster): when the laptop slept, the frozen control plane woke to find
     every heartbeat about 5 minutes old. Within one second it marked all 4
     nodes OFFLINE and every replica LOST. The cluster self-healed, but each
     wake-up caused about 13 no-op repair jobs. The fix: the detector notices
     its own pause (a gap between evaluations of more than 5× its interval)
     and waits one `suspect-after` window before judging. Proven live: a
     70 s control-plane freeze now causes 0 transitions and 0 repair jobs.
   - (h) The same flaw on restart. A control plane started after an 80 s
     outage marked all 4 nodes OFFLINE and 13 chunks LOST, then healed within
     about 90 s. The grace period now also applies at process start. Proven
     live: an 80 s stop and restart causes 0 transitions and 0 LOST.
   - (i) Dashboard: the Overview counted no-op repair commits as "done", the
     amber degraded banner kept showing from stale health data while
     offline, Drain buttons were off-screen in narrow windows, DLQ reasons
     repeated Spring's long wrapper text, and the confirm-dialog label
     wrapped onto three lines. All fixed.
5. Docker Desktop hung twice during the session, the second time after disk
   space ran low. Each time a force-restart restored it; no code changes were
   needed.
6. Objects created before Phase 6 belonged to the placeholder owner
   `local-dev`. Migration V3 reassigned them to `admin`, the default bootstrap
   username.
