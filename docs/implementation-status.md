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
**Phase 5** (OFFLINE detection, repair engine, scrubber, drain) is next.

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
| P3-11 | OFFLINE transition + replica LOST marking | Not started | - | - | - | Phase 5 (the transition is allowed in the table but nothing triggers it yet) |
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
5. Docker Desktop hung twice during the session, the second time after disk
   space ran low. Each time a force-restart restored it; no code changes were
   needed.
6. Admin actions (DLQ replay) use an interim shared token (`BV_ADMIN_TOKEN`)
   until Phase 6 roles exist. The audit actor is `admin-token`, not a person.
7. The public API is unauthenticated until Phase 6. Every request acts as
   owner `local-dev`, and the API is bound to 127.0.0.1 only.
