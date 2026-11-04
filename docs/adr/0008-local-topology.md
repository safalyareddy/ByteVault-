# ADR-0008: Single-host Compose topology sized for a 16 GB laptop

**Status:** Accepted, 2026-11-04

## Context
The target machine is a 16 GB, 10-core MacBook with very little free disk
(about 10 GiB at reconnaissance). Host ports 5432 and 8080 are already taken
by other local services.

## Decision
- Compose services: `postgres` (16.9-alpine), `kafka` (apache/kafka 3.9.1, one
  KRaft broker acting as both broker and controller), `backend`, and
  `node-1`, `node-2`, `node-3`, each with its own named volume.
- Memory limits: Postgres 512 MiB, Kafka 1 GiB (heap 512 MiB), backend
  768 MiB, each node 256 MiB. The total stays under 3.5 GiB.
- Prometheus and Grafana run only under the `monitoring` profile.
- Host ports bind to `127.0.0.1` only. They are remapped to avoid collisions:
  Postgres 55432, backend 8090, nodes 9101-9103.
- The Java images run prebuilt jars on `eclipse-temurin:21-jre-alpine`.
  Skipping a full Maven build image saves about 500 MB of disk.
- Kafka topic auto-creation is disabled, and the control plane declares
  topics explicitly.

## Consequences
- A fresh clone needs `scripts/build.sh` (a local Maven build) before
  `scripts/up.sh`.
- Every container shares one host disk. Capacity numbers reported by nodes
  reflect the Docker VM disk and are **not** independent.
