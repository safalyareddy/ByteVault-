# ADR-0001: Modular-monolith control plane with separate storage-node processes

**Status:** Accepted, 2026-11-04

## Context
The system needs metadata management, upload orchestration, placement, failure
detection, repair, an event outbox, consumers and a public API. Splitting these
into microservices would add network hops, distributed transactions and
deployment overhead, with no measured need for any of it. Storage nodes,
however, *must* be separate processes with separate volumes. Otherwise there is
no real distributed behaviour to show.

## Decision
- One Spring Boot control plane (`backend/`), organised into packages with
  explicit ownership boundaries (see `docs/architecture.md`).
- `storage-node/` is a separate, minimal Spring Boot application. It runs as
  three containers, each with its own identity and volume.
- Storage nodes hold **no** metadata database. They know only chunk IDs and
  bytes.

## Consequences
- Metadata changes and their outbox rows can share a single local transaction.
- Modules may be split out later if measurements justify it. Package
  boundaries keep that cheap.
- If the control plane fails, uploads and repairs stop, but stored bytes are
  safe. This single point of failure is accepted for v1.
