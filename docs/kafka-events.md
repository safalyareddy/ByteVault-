# Kafka topics and event catalogue

Delivery is **at-least-once**, and ordering holds only per key within a
partition. PostgreSQL is authoritative: the control plane always writes events
to `outbox_events` first, and the outbox publisher forwards them (see
[ADR-0006](adr/0006-event-delivery-outbox.md)).

## Topics

The control plane declares these topics at startup, because broker
auto-creation is disabled. Locally they have 3 partitions and replication
factor 1, since there is one broker.

| Topic | Key | Producers | Consumers (group) |
|---|---|---|---|
| `bytevault.object-events` | `objectId` | upload, object | `bv-projection`, `bv-audit` |
| `bytevault.chunk-events` | `objectId:chunkIndex` | upload, repair | `bv-projection` |
| `bytevault.node-events` | `nodeId` | node registry, failure detector | `bv-repair-planner`, `bv-audit` |
| `bytevault.replication-events` | `chunkId` | upload, repair, scrubber | `bv-repair-planner` |
| `bytevault.repair-commands` | `chunkId` | repair planner | `bv-repair-worker` |
| `bytevault.repair-results` | `repairJobId` | repair worker | `bv-projection`, `bv-audit` |
| `bytevault.storage-dlq` | original key | consumer error handlers | operators (guarded replay) |

## Envelope

The Kafka message value is UTF-8 JSON. Headers duplicate `eventId`,
`eventType` and `correlationId`, so messages can be routed without parsing the
body.

```json
{
  "eventId": "0b9a3c1e-6f0c-4c8e-9a51-8d5f3b0c2a11",
  "eventType": "ObjectCompleted",
  "schemaVersion": 1,
  "aggregateType": "OBJECT",
  "aggregateId": "4f6e...",
  "aggregateVersion": 7,
  "occurredAt": "2026-11-04T14:20:00.123Z",
  "correlationId": "c1d2...",
  "payload": { }
}
```

## Event types (v1)

| Topic | eventType | Payload fields |
|---|---|---|
| object-events | `UploadSessionCreated` | objectId, uploadId, ownerId, name, totalSize, chunkSize, chunkCount, replicationFactor |
| object-events | `ObjectCompleted` | objectId, totalSize, chunkCount, sha256 (optional) |
| object-events | `ObjectDegraded` / `ObjectRestored` | objectId, underReplicatedChunks |
| object-events | `ObjectDeleteRequested` / `ObjectDeleted` | objectId, actor |
| chunk-events | `ChunkStored` | objectId, chunkIndex, chunkId, sha256, nodeIds |
| node-events | `NodeStateChanged` | nodeId, from, to, reason, heartbeatAgeMs |
| node-events | `NodeDrainRequested` | nodeId, actor, reason |
| replication-events | `ReplicaVerified` / `ReplicaLost` / `ReplicaCorrupt` | chunkId, nodeId, replicaId, expectedSha256, actualSha256 (if corrupt) |
| repair-commands | `RepairRequested` | repairJobId, chunkId, sourceNodeId, targetNodeId |
| repair-results | `RepairCommitted` / `RepairFailed` / `RepairAbandoned` | repairJobId, chunkId, attempts, durationMs, error |

## Consumer rules

1. Before applying an effect, insert into
   `processed_events(consumer_group, event_id)` in the **same** transaction.
   On a unique violation, skip the event and acknowledge it.
2. Where order matters, ignore an event whose `aggregateVersion` is lower than
   the version already applied.
3. Retry with backoff: initial 500 ms, multiplier 2, max 30 s, ±20% jitter, at
   most 5 attempts. After that, publish to `bytevault.storage-dlq`.
4. Concurrency is set explicitly per listener (default 1 per group).
5. A DLQ record carries `originalTopic`, `originalKey`, `eventId`,
   `failureReason`, `attempts`, `failedAt` and `correlationId`. Replay is an
   ADMIN-only action that is audited, records the operator, reason and replay
   attempt, and keeps the original `eventId`.

## Status

The topic declarations are implemented and verified in Phase 1. If Kafka is unreachable at startup, `TopicInitializer` retries creation. The outbox publisher,
consumers, retries, DLQ and replay are **Phase 4** and not implemented yet.
See [implementation-status.md](implementation-status.md).
