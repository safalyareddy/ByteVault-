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

## Implementation notes (Phase 5)
- When no eligible target exists, the job returns to PENDING with
  `last_error = waiting for capacity…`. That attempt isn't counted, and the
  job retries every `BV_REPAIR_CAPACITY_WAIT`. Running short of nodes is a
  cluster condition, not a failure of the job, and it must never lead to
  ABANDONED or to a lower RF.
- A corrupt copy on the chosen target is deleted from that node before the
  rewrite. That's safe because the bytes are known to be bad and a verified
  source is already in the spool.
- Object status is recomputed under a `SELECT … FOR UPDATE` on the object row,
  so concurrent repairs of different chunks see each other's commits.
- Under contention with `markOffline`, which locks many rows, PostgreSQL may
  abort one side as a deadlock victim. Both sides are retried: the repair job
  by its backoff, the detector by its next tick.

## Consequences
- A crashed worker delays a repair by at most one lease duration.
- Repair speed is deliberately capped to keep the laptop responsive.
