# ADR-0005: COMPLETE only when all RF replicas are verified

**Status:** Accepted, 2026-11-04

## Context
A write could be acknowledged after a quorum (2 of 3) or only after all
replicas. Quorum writes improve availability, but they let an object be
COMPLETE while under-replicated from its very first second.

## Decision
- v1 requires **all** `replication_factor` replicas to be VERIFIED before a
  chunk counts as stored and before the object can become `COMPLETE`.
- A chunk PUT with fewer than RF eligible nodes is rejected with
  `503 INSUFFICIENT_HEALTHY_NODES`, and the object stays `UPLOADING`.
- If some replica writes fail, the chunk PUT returns an error. The replicas
  that did succeed are kept as `VERIFIED`. Retrying the PUT is idempotent and
  only writes the missing replicas.
- `DEGRADED` applies to objects that were COMPLETE and later lost replicas
  (node offline or corruption). They stay downloadable while at least one
  verified replica exists per chunk.
- The replication factor is fixed for each object when its upload starts and
  is never lowered silently.

## Consequences
- With 3 nodes and RF=3, losing any one node blocks new uploads until it
  returns or a node is added. This is the honest result of the policy, and the
  API reports it explicitly.
