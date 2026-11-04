# ADR-0006: Transactional outbox, at-least-once delivery, idempotent consumers

**Status:** Accepted, 2026-11-04

## Context
State changes in PostgreSQL must produce Kafka events without dual-write
inconsistency, and they must survive Kafka outages.

## Decision
- Domain state changes and an `outbox_events` row are written in **one**
  PostgreSQL transaction.
- A publisher claims batches with
  `SELECT ... WHERE published_at IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT n`.
  It publishes each event with its aggregate key, waits for the broker ack,
  then sets `published_at`. Publish failures increment `publish_attempts` and
  record `last_error`, and the row is retried with backoff.
- A crash after the publish but before marking the row produces a duplicate,
  which is accepted.
- Consumers use named groups and **deduplicate on `event_id`** through
  `processed_events(consumer_group, event_id)` inside the same transaction as
  their side effect. They also check `aggregate_version` where order matters.
- Retries use bounded exponential backoff with jitter. After they run out, the
  event goes to `storage-dlq` with the original topic, key, event ID, reason,
  attempt count, timestamp and correlation ID.
- Replaying from the DLQ is an audited ADMIN action that keeps the original
  `event_id`.
- No end-to-end exactly-once guarantee is claimed.

## Consequences
- During a Kafka outage, the outbox backlog grows. That is observable as
  `bv_outbox_backlog`, and it drains once the broker is back.
- Ordering is guaranteed only per key within a partition.
