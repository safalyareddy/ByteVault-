# ADR-0002: Versioned, HMAC-signed internal HTTP protocol for storage nodes

**Status:** Accepted, 2026-11-04

## Context
The control plane must PUT, GET, DELETE and verify chunks on nodes, and nodes
must send heartbeats. Choices considered: gRPC, raw TCP, or HTTP/1.1 with
streaming bodies. Authentication options considered: mTLS, or a shared-secret
HMAC.

## Decision
- HTTP/1.1, path-versioned under `/internal/v1/`, with streaming request and
  response bodies. This is simple to debug with curl, and Spring MVC supports
  streaming without loading whole bodies into memory.
- Every request is authenticated with **HMAC-SHA256 using a per-node secret**.
  The signature covers the method, path, node ID, timestamp and declared
  content checksum. Timestamps must fall within ±60 s. Heartbeats use the same
  scheme in the opposite direction.
- The full contract is in `docs/storage-node-protocol.md`.

## Consequences
- With no TLS on the local Compose network, bytes travel in cleartext. That is
  acceptable on a single host. Deployed environments must add TLS (see the
  threat model).
- A signature doesn't cover the body bytes directly. Instead, the declared
  SHA-256 is signed and the node verifies the body against it before
  acknowledging. A tampered body fails that checksum check.
- Replay is possible only inside the 60 s window, and the PUT/DELETE
  operations are idempotent, which limits the impact.
