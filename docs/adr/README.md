# Architecture decision records

| ADR | Title | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | Modular-monolith control plane with separate storage-node processes | Accepted |
| [0002](0002-storage-node-protocol.md) | Versioned, HMAC-signed internal HTTP protocol for storage nodes | Accepted |
| [0003](0003-chunk-size.md) | Fixed 4 MiB default chunk size | Accepted |
| [0004](0004-placement-rendezvous-hashing.md) | Rendezvous (HRW) hashing for replica placement | Accepted |
| [0005](0005-write-completion-policy.md) | COMPLETE only when all RF replicas are verified | Accepted |
| [0006](0006-event-delivery-outbox.md) | Transactional outbox, at-least-once delivery, idempotent consumers | Accepted |
| [0007](0007-repair-concurrency.md) | Lease-based repair jobs with bounded concurrency | Accepted |
| [0008](0008-local-topology.md) | Single-host Compose topology sized for a 16 GB laptop | Accepted |
| [0009](0009-pinned-versions.md) | Pinned toolchain and dependency versions | Accepted |
| [0010](0010-push-heartbeats-and-admission.md) | Signed push heartbeats with an admission probe | Accepted |
