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
**Phase 2** (single-node chunk storage) is next.

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
| P2-* | Single-node chunk storage | Not started | | | | |
| P3-* | Multi-node placement and replication | Not started | | | | |
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
