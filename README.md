# ByteVault

ByteVault is a self-healing distributed object store, built as an engineering
demonstration. It stores objects as SHA-256-checksummed chunks replicated
across independent storage-node processes. PostgreSQL holds the authoritative
metadata, Kafka carries domain events through a transactional outbox, and the
system detects failures and repairs from verified replicas.

> **Status:** Phases 0–3 are done. Chunked upload and
> download work, with 3 checksum-verified replicas on distinct nodes, signed node
> heartbeats with admission and failure detection,
> read-path corruption detection and asynchronous delete. Kafka publishing,
> automatic repair, the scrubber, authentication and the dashboard are **not
> implemented yet**.
> The exact state, with test evidence, is in
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
- About 4 GB of free disk for images, dependencies and volumes

## Quick start

```bash
scripts/gen-env.sh      # creates .env with random local secrets (never overwrites)
scripts/build.sh        # mvn package (unit tests) + frontend build
scripts/up.sh           # starts the stack and waits for /api/v1/health
scripts/status.sh       # container state + every health endpoint
scripts/bv.sh nodes     # node inventory
scripts/bv.sh upload ./some-file          # prints the object id
scripts/bv.sh download <objectId> ./copy  # verifies size; compare with shasum -a 256
cd frontend && npm run dev   # console at http://127.0.0.1:5173
```

Stopping and resetting:

```bash
scripts/down.sh           # stops containers, KEEPS all data volumes
scripts/reset-volumes.sh  # DESTRUCTIVE: deletes metadata, Kafka log and chunk data (asks for confirmation)
```

Optional monitoring (Prometheus on :9090, Grafana on :3001):
`scripts/up.sh --profile monitoring`.

## Ports (all bound to 127.0.0.1)

| Service | Port |
|---|---|
| Control plane API | 8090 |
| PostgreSQL | 55432 |
| Kafka | 9092 |
| storage nodes 1–3 | 9101–9103 |
| Frontend dev server | 5173 |

## Tests

```bash
mvn -B -ntp test     # unit tests, no Docker needed
mvn -B -ntp verify   # + integration tests against real Postgres/Kafka (Testcontainers, needs Docker)
cd frontend && npm run typecheck && npm run build
```

## Documentation

- [Architecture](docs/architecture.md) and [ADRs](docs/adr/README.md)
- [State machines](docs/state-machines.md)
- [Kafka topics and events](docs/kafka-events.md)
- [Storage-node protocol](docs/storage-node-protocol.md)
- [Security threat model](docs/security-threat-model.md)
- [Implementation status](docs/implementation-status.md)

## Limitations

ByteVault is an engineering demonstration on a single machine, not a
production storage system. All nodes share one host, one disk and one Docker
VM, so the demo shows process and node failures, not independent physical
failure domains. Kafka runs as a single broker. Performance claims will be
made only with reproducible benchmark output.
