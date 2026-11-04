# ADR-0007: Lease-based repair jobs with bounded concurrency

**Status:** Accepted, 2026-11-04

## Context
Repair copies chunk bytes between nodes, which can take seconds. Holding a
database transaction or row lock across that copy would block other work and
risk long-held locks if a process crashes mid-copy.

## Decision
- A `repair_jobs` row is the unit of work. Its states are
  `PENDING -> CLAIMED -> COPYING -> VERIFYING -> COMMITTED`, plus `FAILED`
  (retryable) and `ABANDONED` (terminal).
- A worker claims a job by compare-and-set: it updates `state` and
  `lease_until = now() + leaseDuration` only where the state matches and the
  lease is null or expired. Short transactions are used only at state
  boundaries.
- A partial unique index (`chunk_id WHERE state NOT IN ('COMMITTED','ABANDONED')`)
  allows only one active repair per chunk.
- Concurrency limits: `BV_REPAIR_MAX_CONCURRENT` (default 2) overall and at
  most one job per target node at a time.
- Commit is a transaction that inserts the new replica as VERIFIED, and only
  afterwards marks the stale replica LOST/CORRUPT for asynchronous cleanup.
- If the lease expires, another worker can take the job over. Every step is
  idempotent because node PUTs are idempotent by chunk ID and checksum.

## Consequences
- A crashed worker delays a repair by at most one lease duration.
- Repair speed is deliberately capped to keep the laptop responsive.
