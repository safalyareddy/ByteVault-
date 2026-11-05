# Data model

PostgreSQL is the source of truth for metadata, and Flyway manages the
schema. The migrations are in `backend/src/main/resources/db/migration/`:
- **V1:** baseline schema with its invariants
- **V2:** event timeline, statistics projection and DLQ records
- **V3:** users and tokens

No file bytes and no plaintext secrets are stored here.

```mermaid
erDiagram
    objects ||--o{ chunks : "split into"
    objects ||--o| upload_sessions : "created by"
    chunks ||--o{ chunk_replicas : "stored as"
    storage_nodes ||--o{ chunk_replicas : "holds"
    chunks ||--o{ repair_jobs : "repaired by"
    storage_nodes ||--o{ repair_jobs : "source / target"
    users ||--o{ api_tokens : "issued"

    objects { uuid id PK; varchar owner_id; varchar name; bigint total_size; int chunk_size; int chunk_count; varchar status; smallint replication_factor; bigint version }
    chunks { uuid id PK; uuid object_id FK; int chunk_index; bigint byte_offset; int byte_size; char sha256; varchar status; bigint version }
    chunk_replicas { uuid id PK; uuid chunk_id FK; varchar node_id FK; varchar state; char stored_checksum; timestamptz last_verified_at; bigint version }
    storage_nodes { varchar id PK; varchar endpoint; varchar state; bigint capacity_bytes; bigint usable_bytes; timestamptz last_heartbeat_at; bigint version }
    upload_sessions { uuid id PK; uuid object_id FK; varchar owner_id; varchar idempotency_key; char request_fingerprint; varchar status; timestamptz expires_at }
    repair_jobs { uuid id PK; uuid chunk_id FK; varchar source_node_id FK; varchar target_node_id FK; varchar reason; varchar state; int attempts; varchar lease_owner; timestamptz lease_until }
    users { uuid id PK; varchar username; varchar password_hash; varchar role; bool disabled }
    api_tokens { char token_hash PK; uuid user_id FK; timestamptz expires_at; timestamptz revoked_at }
```

Tables that stand alone:

| Table | Holds |
|---|---|
| `outbox_events` | domain events written in the same transaction as their state change, until they are published |
| `processed_events` | per-consumer-group deduplication by event ID |
| `audit_events` | the append-only audit log; a trigger rejects UPDATE and DELETE |
| `event_log`, `object_stats`, `projection_versions` | Kafka-built read models that can be rebuilt |
| `dlq_events` | dead-lettered messages kept for inspection and replay |

## Invariants enforced by the database

| Invariant | How |
|---|---|
| A chunk has at most one replica per physical node | `UNIQUE (chunk_id, node_id)` |
| A VERIFIED replica records the checksum it was verified against | `CHECK (state <> 'VERIFIED' OR stored_checksum IS NOT NULL)` |
| At most one active repair per chunk | partial unique index on `repair_jobs(chunk_id)` while not COMMITTED or ABANDONED |
| A repair never copies a node to itself | `CHECK (source_node_id <> target_node_id)` |
| One upload session per owner and idempotency key | `UNIQUE (owner_id, idempotency_key)` |
| The audit log can't be rewritten | `BEFORE UPDATE OR DELETE` trigger raises an error |
| States are closed sets | a `CHECK` on every state and status column |

State transitions are enforced in code with compare-and-set updates on
`version` or `state`; see [state-machines.md](state-machines.md).
