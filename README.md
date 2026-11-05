# ByteVault

ByteVault is a self-healing distributed object store, built as an engineering
demonstration. It stores objects as SHA-256-checksummed chunks replicated
across independent storage-node processes. PostgreSQL holds the authoritative
metadata, Kafka carries domain events through a transactional outbox, and the
system detects failures and repairs from verified replicas.

> **Status:** all planned phases are implemented and verified.
> - Chunked storage with 3 verified replicas on distinct nodes
> - Signed heartbeats with failure detection
> - Self-healing: repair, scrubbing, drain and reconciliation
> - Kafka events through a transactional outbox, with idempotent consumers, a
>   DLQ and replay
> - Authentication with USER/ADMIN roles
> - An operations console
> - Measured benchmarks
>
> Evidence for every claim, and an explicit list of what's **not** done, is in
> [docs/implementation-status.md](docs/implementation-status.md).

## Layout

```
backend/         Spring Boot control plane (API, metadata, placement, outbox, repair)
storage-node/    Independent storage-node process (one per container/volume)
node-protocol/   Shared node-protocol constants, HMAC request signing, SHA-256 helpers
frontend/        React + TypeScript operations console (Vite)
infra/compose/   Docker Compose topology (Postgres, Kafka KRaft, backend, 3 nodes, monitoring profile)
infra/monitoring Prometheus scrape config
docs/            Architecture, ADRs, state machines, Kafka catalogue, node protocol, threat model
scripts/         build / up / down / status / gen-env / reset-volumes (destructive, guarded)
```

## Prerequisites (macOS)

- Docker Desktop, running (`docker info` must respond)
- JDK 21 or newer. The bytecode targets Java 21, and the containers run Temurin 21.
- Maven 3.9+
- Node.js 22 with npm
- `python3`, `jq`, `curl` and `openssl`, used by the CLI, the smoke test and the env generator (all present on a stock
  macOS with Homebrew `jq`)
- At least 6 GB of free disk: images, dependency caches, the Docker build cache and node volumes

## Quick start

```bash
scripts/gen-env.sh      # creates .env with random local secrets (never overwrites)
scripts/build.sh        # mvn package (unit tests) + frontend build
scripts/up.sh           # starts the stack and waits for /api/v1/health
scripts/status.sh       # container state + every health endpoint
scripts/bv.sh login     # admin password = BV_BOOTSTRAP_ADMIN_PASSWORD in .env
scripts/bv.sh nodes     # node inventory (ADMIN)
scripts/bv.sh upload ./some-file          # prints the object id
scripts/bv.sh download <objectId> ./copy  # verifies size; compare with shasum -a 256
python3 -I scripts/bvtool.py smoke   # end-to-end check; prints SMOKE TEST PASSED
```

Operations console (run it in a second terminal; it stays in the foreground):

```bash
cd frontend && npm run dev   # http://127.0.0.1:5173, sign in as admin
```

On a machine where the Maven, npm and Docker image caches are already warm,
the quick start takes about a minute from a fresh clone to a passing smoke
test. A new machine first downloads roughly 1–2 GB of dependencies and
images.

Stopping and resetting:

```bash
scripts/down.sh           # stops containers, KEEPS all data volumes
scripts/reset-volumes.sh  # DESTRUCTIVE: deletes metadata, Kafka log and chunk data (asks for confirmation)
```

Optional profiles: `scripts/up.sh --profile spare` adds node-4, so a lost
node's copies have somewhere to go. With only 3 nodes and RF=3, repair waits
for capacity. `--profile monitoring` adds Prometheus (:9090) and Grafana
(:3001). That profile hasn't been exercised yet, and no Grafana dashboards
are provisioned.

Fault injection, all guarded (see `scripts/fault/`):

```bash
scripts/fault/stop-node.sh node-2 --yes     # then scripts/fault/start-node.sh node-2
scripts/fault/corrupt-chunk.sh <objectId> <chunkIndex> node-1   # disposable-* objects only
scripts/fault/pause-kafka.sh stop           # then: start
```

## Ports (all bound to 127.0.0.1)

| Service | Port |
|---|---|
| Control plane API | 8090 |
| PostgreSQL | 55432 |
| Kafka | 9092 |
| storage nodes 1–3 (node-4 with `--profile spare`) | 9101–9103 (9104) |
| Frontend dev server | 5173 |
| Prometheus / Grafana (`--profile monitoring`) | 9090 / 3001 |

## Tests

```bash
mvn -B -ntp test     # unit tests, no Docker needed
mvn -B -ntp verify   # + integration tests against real Postgres/Kafka (Testcontainers, needs Docker)
cd frontend && npm run lint && npm run typecheck && npm test && npm run build
```

## Documentation

- [Demo script](docs/demo-script.md) (about 7 minutes) and [benchmark report](docs/benchmark-report.md)
- [OpenAPI spec](docs/openapi.json) (also served at `/v3/api-docs`), [data model](docs/data-model.md), [testing guide](docs/testing.md)
- [Architecture](docs/architecture.md) and [ADRs](docs/adr/README.md)
- [State machines](docs/state-machines.md)
- [Kafka topics and events](docs/kafka-events.md)
- [Storage-node protocol](docs/storage-node-protocol.md)
- [Security threat model](docs/security-threat-model.md)
- [Operations runbook](docs/operations-runbook.md): accounts, backup/restore, reconciliation, failure handling
- [Implementation status](docs/implementation-status.md) and [production-readiness audit](docs/production-readiness.md)

## Limitations

ByteVault is an engineering demonstration on a single machine, not a
production storage system. All nodes share one host, one disk and one Docker
VM, so the demo shows process and node failures, not independent physical
failure domains. Kafka runs as a single broker. Performance claims will be
made only with reproducible benchmark output.
