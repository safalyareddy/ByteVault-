# Storage-node protocol v1

The protocol is internal HTTP/1.1 between the control plane and storage nodes,
and every path is prefixed with `/internal/v1`. Design rationale:
[ADR-0002](adr/0002-storage-node-protocol.md).

## Authentication

Every request except `GET /internal/v1/health` must carry these headers:

| Header | Value |
|---|---|
| `X-BV-Node-Id` | the target node's ID (control plane → node) or the sender's ID (heartbeat) |
| `X-BV-Timestamp` | Unix epoch milliseconds |
| `X-BV-Content-SHA256` | lowercase hex SHA-256 of the body, or `-` when there is no body |
| `X-BV-Signature` | lowercase hex `HMAC-SHA256(secret, canonical)` |

```
canonical = METHOD + "\n" + PATH + "\n" + NODE_ID + "\n" + TIMESTAMP + "\n" + CONTENT_SHA256
```

- `PATH` is the raw request path without the query string.
- `secret` is that node's `BV_NODE_AUTH_SECRET`, at least 32 bytes. The
  control plane knows each node's secret through `BV_NODE_SECRETS`.
- Signatures are compared in constant time, and the timestamp must be within
  ±60 s. A failure returns `401` with no detail about which check failed.
- A node rejects a request whose `X-BV-Node-Id` isn't its own ID with `403`.

## Endpoints

### `GET /internal/v1/health` (unauthenticated, no secrets)

```json
{"nodeId":"node-1","protocolVersion":1,"status":"UP",
 "dataDirWritable":true,"capacityBytes":62725623808,"usableBytes":10712121344,
 "checkedAt":"2026-11-04T14:20:00Z"}
```
`status` is `DOWN` (returned with HTTP 503) if the data directory isn't writable.

### `PUT /internal/v1/chunks/{chunkId}` (Phase 2)

- `chunkId` must be a canonical UUID; anything else returns `400`.
- `Content-Length` is required and must be at most `BV_NODE_MAX_CHUNK_BYTES`
  (`411`/`413` otherwise).
- `X-BV-Content-SHA256` is the expected checksum of the body.
- Write sequence: stream into `tmp/<chunkId>.<random>.part` with a 64 KiB
  buffer while computing SHA-256 → check byte count and checksum → `fsync` the
  file → atomic rename to `chunks/<aa>/<bb>/<chunkId>.chunk` (`aa`/`bb` are
  the first 4 hex characters of the UUID) → `fsync` the directory where
  supported → respond.
- Responses: `201 {chunkId, sha256, sizeBytes}` for a new write. `200` with
  the same body when the chunk already exists with the same checksum
  (idempotent). `409 CHUNK_CONFLICT` when it exists with a different checksum.
  `422 CHECKSUM_MISMATCH` or `LENGTH_MISMATCH` when verification fails, in
  which case the temp file is deleted.

### `GET /internal/v1/chunks/{chunkId}` (Phase 2)

Streams the bytes with `X-BV-Stored-SHA256` set to the checksum computed at
write time, which is kept in a sidecar file. The caller **must** re-hash the
stream and reject it on mismatch. Returns `404` if the chunk is absent.

### `POST /internal/v1/chunks/{chunkId}/verify` (Phase 5)

Re-reads the chunk from disk and returns
`{chunkId, sha256, sizeBytes, verifiedAt}`, for scrubbing without transferring
the bytes.

### `DELETE /internal/v1/chunks/{chunkId}` (Phase 2)

Idempotent: `204` whether the chunk existed or not.

### `GET /internal/v1/chunks?after={chunkId}&limit={n}` (Phase 5)

Pages through stored chunk IDs, for reconciliation after a metadata restore.

### Heartbeat: `POST {controlPlane}/internal/v1/nodes/{nodeId}/heartbeat` (node → control plane)

Sent every `BV_HEARTBEAT_INTERVAL` and signed with the node's own secret. Here
`X-BV-Node-Id` is the sending node, and `X-BV-Content-SHA256` is the SHA-256 of
the exact JSON body bytes.

```json
{"nodeId":"node-1","protocolVersion":1,"advertisedEndpoint":"http://node-1:9100",
 "status":"UP","dataDirWritable":true,"capacityBytes":240120725504,"usableBytes":216475791360,
 "sentAt":"2026-11-04T14:20:00Z"}
```

Responses:
- `200 {"nodeId","state"}`: the node's state as the control plane now
  records it.
- `401`: an unknown node, a bad signature, an expired timestamp, mismatched
  node IDs, or a body that doesn't match its declared checksum.
- `400`: malformed JSON.

The admission policy and the failure detector are described in
[ADR-0010](adr/0010-push-heartbeats-and-admission.md).

## Filesystem safety

- Paths are built only from a validated UUID. Client filenames never reach a
  node.
- After resolving a path, the node checks that `path.normalize()` starts with
  the real path of the data directory and that the result isn't a symlink.
- The data directory is created with permissions `rwx------`, and the node
  process runs as a non-root user in its container.
