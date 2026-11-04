# ADR-0004: Rendezvous (HRW) hashing for replica placement

**Status:** Accepted, 2026-11-04

## Context
Placement must be deterministic and testable, spread chunks evenly, keep
movement minimal when nodes join or leave, and guarantee distinct nodes per
chunk. Consistent hashing with virtual nodes needs a ring and tuning of the
vnode count. Rendezvous hashing gives the same minimal-disruption property in a
few lines and naturally produces an ordered list of distinct nodes.

## Decision
- `key = objectId + ":" + chunkIndex` (lowercase canonical UUID string).
- For each **eligible** node: `score = unsigned 64-bit big-endian value of the
  first 8 bytes of SHA-256(UTF-8(key + "|" + nodeId))`.
- Sort by score descending. Ties are broken by `nodeId` ascending (lexical).
  Take the first `replicationFactor` nodes.
- **Eligible** means state `HEALTHY`, not `DRAINING`, and
  `free_bytes >= chunkSize + reserveBytes`.
- If fewer than RF nodes are eligible, placement returns an
  `InsufficientNodes` result. It **never** returns fewer nodes silently.
- Repair targets use the same ordering, skipping nodes that already hold a
  replica.

## Consequences
- O(N log N) per chunk, which is trivial for N=3.
- When a node leaves, only chunks that ranked it in their top RF move.
- Unit tests pin known inputs to expected outputs to prove determinism.
