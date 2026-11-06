# Security threat model

Scope: a local, single-host Docker Compose deployment, analysed per STRIDE
category. **Status** says whether a mitigation exists today. Anything not
marked *Implemented* is not yet a protection.

## Assets
Stored object bytes; object metadata and ownership; node secrets; database and
Kafka credentials; the audit log; admin capabilities (drain, replay, repair
retry).

## Threats and mitigations

| # | Threat (STRIDE) | Mitigation | Phase | Status |
|---|---|---|---|---|
| T1 | Spoofed storage node sends fake heartbeats or receives chunks (S) | Per-node HMAC secrets, node-ID binding, ±60 s timestamp window | 2–3 | Implemented (control plane → node); node → control plane heartbeats are Phase 3–5 |
| T2 | Attacker on the network tampers with chunk bytes (T) | The declared SHA-256 is signed and the node verifies the body before ack; the control plane re-verifies on read | 2 | Implemented |
| T3 | Bit rot or manual corruption on disk (T) | Periodic scrubber, read-path verification, repair from a verified replica | 5 | Read-path verification implemented; scrubber and repair planned |
| T4 | Path traversal through a filename or chunk ID (T/E) | Only server-generated UUID paths, canonical UUID validation, normalised containment check, symlink rejection | 2 | Implemented |
| T5 | A user reads or deletes another user's objects (I/E) | Ownership checks in services, USER/ADMIN roles | 6 | Owner scoping implemented in every query; authentication is Phase 6 (all requests are `local-dev` until then) |
| T6 | A non-admin drains nodes or replays the DLQ (E) | ADMIN role and an audit row for every admin action | 5–6 | Planned |
| T7 | Upload floods exhaust disk or memory (D) | `BV_MAX_UPLOAD_BYTES`, per-chunk size limit, bounded concurrent chunk uploads, rate limits, streaming I/O | 2, 6 | Implemented except rate limits (Phase 6) |
| T8 | Secrets leak through Git or logs (I) | `.env` is git-ignored; `.env.example` has placeholders only; health endpoints expose no config; no secrets in log lines | 1 | Implemented |
| T9 | DB/Kafka exposed beyond localhost (I) | Compose publishes ports on `127.0.0.1` only | 1 | Implemented |
| T10 | Duplicate or replayed events cause double effects (T) | `processed_events` dedupe, idempotent operations | 4 | Planned |
| T11 | Repudiation of admin actions (R) | Append-only `audit_events` with actor, action, correlation ID | 5–6 | Planned (table exists) |
| T12 | Containers running as root (E) | Non-root user in the backend and node images | 1 | Implemented |
| T13 | Cleartext traffic (I/T) | Acceptable on a single-host Compose network only; TLS is **required** for any deployment beyond that | - | Accepted risk (local) |

## Accepted risks (v1)
- One host, one disk, one Docker VM: no independent failure domains.
- A single Kafka broker with no TLS or SASL locally.
- A single control-plane instance, which is a single point of failure for
  writes.
- HMAC replay is possible within the 60 s window. The impact is limited
  because operations are idempotent.
