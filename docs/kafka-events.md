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
| `bytevault.object-events` | `objectId` | upload, object | `bv-timeline`, `bv-object-stats` |
| `bytevault.chunk-events` | `objectId:chunkIndex` | upload, repair | `bv-timeline` |
| `bytevault.node-events` | `nodeId` | node lifecycle, failure detector, drain | `bv-timeline` |
| `bytevault.replication-events` | `chunkId` | download, scrubber, OFFLINE detection | `bv-timeline` |
| `bytevault.repair-commands` | `chunkId` | repair planner (`RepairRequested`) | none (history only; workers claim jobs from PostgreSQL with leases, ADR-0007) |
| `bytevault.repair-results` | `repairJobId` | repair worker (`RepairCommitted`/`RepairFailed`/`RepairAbandoned`) | `bv-timeline` |
| `bytevault.storage-dlq` | original key | consumer error handler | `bv-dlq-recorder` → `dlq_events`, guarded replay |

### Consumer groups (implemented)

| Group | Effect | Idempotency |
|---|---|---|
| `bv-timeline` | Inserts every event into `event_log`, served at `GET /api/v1/events` | `processed_events` plus the `event_log` primary key |
| `bv-object-stats` | Maintains the `object_stats` counters, served at `GET /api/v1/stats` next to the authoritative counts | `processed_events` plus an `aggregateVersion` check (`projection_versions`) |
| `bv-dlq-recorder` | Records each DLQ message in `dlq_events` | unique `(dlq_partition, dlq_offset)` |

Consumers ack each record individually (`AckMode.RECORD`), after the effect
transaction has committed. If a crash happens between that commit and the ack,
the record is delivered again and skipped as a duplicate.

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

## Publisher

`OutboxPublisher` polls every 500 ms. Each cycle:
1. Claims up to 100 rows with `FOR UPDATE SKIP LOCKED`, ordered by
   `created_at`.
2. Sends them with their aggregate key and waits for `acks=all`.
3. Marks exactly the acknowledged prefix as published.

On the first failure it stops and pauses *globally* for
`min(500 ms × 2ⁿ, 30 s) ± 20%`. That way, later events never overtake an
earlier event with the same key. Failed rows get `publish_attempts` and
`last_error`.

Metrics: `bv_outbox_backlog`, `bv_outbox_oldest_unpublished_age_seconds`,
`bv_outbox_published_total`, `bv_outbox_publish_failures_total`. Consumer lag
comes from the standard Kafka client metrics in `/actuator/prometheus`
(`kafka_consumer_fetch_manager_records_lag_max`).

Note: the claim transaction stays open while a batch is sent. The producer's
timeouts (`max.block.ms` and `request.timeout.ms` 5 s, `delivery.timeout.ms`
12 s) bound how long that can be. The publisher assumes it's the only
instance; extra instances would be safe from double-claiming, but they could
interleave keys.

## Replay

Replay is `POST /api/v1/admin/dlq/{id}/replay` with body `{"reason": "..."}`.
It requires an ADMIN bearer token. It:
- claims the entry with compare-and-set, so it can only be replayed once
- re-publishes the original key and value to the original topic, with the
  `eventId` unchanged, plus the headers `bv-replay-attempt` and
  `bv-replayed-by`
- writes a `DLQ_REPLAY` audit row with the reason and attempt number

Consumers that had already processed the event skip it as a duplicate. If the
replayed event fails again, it creates a new DLQ entry.

## Status

Implemented and verified in Phase 4; see
[implementation-status.md](implementation-status.md).
