# ADR-0010: Signed push heartbeats with an admission probe

**Status:** Accepted, 2026-11-04

## Context
In Phase 2 the control plane polled each node's unauthenticated health
endpoint. That polling can't prove a node's identity, and anything that can
reach the port can impersonate it. The spec asks for nodes to report heartbeats
and for a JOINING → HEALTHY lifecycle.

## Decision
- Each node `POST`s a heartbeat to
  `{controlPlane}/internal/v1/nodes/{nodeId}/heartbeat` every
  `BV_HEARTBEAT_INTERVAL`. The request is signed with the node's own secret
  using the protocol v1 HMAC scheme. `X-BV-Content-SHA256` is the SHA-256 of
  the JSON body, so the body itself is authenticated.
- The control plane accepts heartbeats only from nodes in `BV_NODES` whose
  signature verifies with that node's secret. The path node ID, the header
  node ID and the body node ID must all match. Every failure returns `401`
  with no detail.
- The first valid heartbeat registers the node as `JOINING`.
- **Admission policy, JOINING → HEALTHY:**
  - `BV_ADMISSION_HEARTBEATS` consecutive valid heartbeats (default 2), each
    with status `UP`, a writable data directory and a supported protocol
    version.
  - Then an admission probe: a signed `GET` of the nil-UUID chunk to the
    *configured* endpoint must return `404`. A `404` proves the control plane
    can reach the node and that both sides hold the same secret, which a `401`
    or `403` would disprove.
- **Failure detection:** the age of `last_heartbeat_at` decides it. A HEALTHY
  node with no heartbeat for `BV_SUSPECT_AFTER` becomes SUSPECT. A heartbeat
  reporting `DOWN` or an unwritable data directory moves a HEALTHY node to
  SUSPECT at once. A valid `UP` heartbeat moves a SUSPECT node back to
  HEALTHY. The OFFLINE transition and replica-LOST handling come in Phase 5.
- The endpoint the node advertises is recorded, but the control plane always
  dials the endpoint from its own configuration, because a node must not be
  able to redirect traffic.
- Pull polling is removed.

## Consequences
- A node whose secret differs from the control plane's never becomes eligible
  for placement.
- Liveness now depends on node → control-plane reachability. A one-way
  partition, where the node can't reach the control plane but the control
  plane can reach the node, makes the node SUSPECT. That's the conservative
  choice.
- The admission state, a count of consecutive heartbeats, is kept in memory.
  After a control-plane restart, a JOINING node simply needs the required
  number of heartbeats again.
