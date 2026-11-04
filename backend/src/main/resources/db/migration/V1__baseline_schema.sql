-- ByteVault baseline schema. PostgreSQL is the authoritative metadata store (ADR-0006).
-- State values and allowed transitions: docs/state-machines.md.
-- No file bytes and no secrets are ever stored here.

-- Storage nodes use their configured BV_NODE_ID as a natural key: it is the stable physical
-- identity that the distinct-replica rule is defined over.
CREATE TABLE storage_nodes (
    id                VARCHAR(32)  PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9-]{0,31}$'),
    endpoint          VARCHAR(255) NOT NULL,
    state             VARCHAR(16)  NOT NULL
                      CHECK (state IN ('JOINING', 'HEALTHY', 'SUSPECT', 'OFFLINE', 'DRAINING')),
    protocol_version  INTEGER      NOT NULL CHECK (protocol_version > 0),
    capacity_bytes    BIGINT       NOT NULL DEFAULT 0 CHECK (capacity_bytes >= 0),
    used_bytes        BIGINT       NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
    usable_bytes      BIGINT       NOT NULL DEFAULT 0 CHECK (usable_bytes >= 0),
    last_heartbeat_at TIMESTAMPTZ,
    state_changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_storage_nodes_state_heartbeat ON storage_nodes (state, last_heartbeat_at);

CREATE TABLE objects (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id           VARCHAR(64)   NOT NULL,
    name               VARCHAR(1024) NOT NULL CHECK (length(name) > 0),
    content_type       VARCHAR(255),
    total_size         BIGINT        NOT NULL CHECK (total_size >= 0),
    chunk_size         INTEGER       NOT NULL CHECK (chunk_size > 0),
    chunk_count        INTEGER       NOT NULL CHECK (chunk_count >= 0),
    sha256             CHAR(64)      CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    status             VARCHAR(16)   NOT NULL
                       CHECK (status IN ('INITIATED', 'UPLOADING', 'VERIFYING', 'COMPLETE', 'FAILED',
                                         'DEGRADED', 'DELETING', 'DELETED')),
    replication_factor SMALLINT      NOT NULL CHECK (replication_factor BETWEEN 1 AND 5),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ,
    version            BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_objects_owner_created ON objects (owner_id, created_at DESC) WHERE status <> 'DELETED';
CREATE INDEX ix_objects_status ON objects (status);

CREATE TABLE chunks (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id   UUID        NOT NULL REFERENCES objects (id),
    chunk_index INTEGER     NOT NULL CHECK (chunk_index >= 0),
    byte_offset BIGINT      NOT NULL CHECK (byte_offset >= 0),
    byte_size   INTEGER     NOT NULL CHECK (byte_size > 0),
    sha256      CHAR(64)    CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    status      VARCHAR(20) NOT NULL
                CHECK (status IN ('PENDING', 'STORED', 'UNDER_REPLICATED', 'DELETING', 'DELETED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_chunks_object_index UNIQUE (object_id, chunk_index)
);
CREATE INDEX ix_chunks_under_replicated ON chunks (updated_at) WHERE status = 'UNDER_REPLICATED';

CREATE TABLE chunk_replicas (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id         UUID        NOT NULL REFERENCES chunks (id),
    node_id          VARCHAR(32) NOT NULL REFERENCES storage_nodes (id),
    state            VARCHAR(16) NOT NULL
                     CHECK (state IN ('PENDING', 'VERIFIED', 'FAILED_WRITE', 'CORRUPT', 'LOST',
                                      'DELETING', 'DELETED')),
    stored_checksum  CHAR(64)    CHECK (stored_checksum ~ '^[0-9a-f]{64}$'),
    stored_at        TIMESTAMPTZ,
    last_verified_at TIMESTAMPTZ,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,
    -- Invariant: at most one replica of a chunk per physical node.
    CONSTRAINT uq_chunk_replicas_chunk_node UNIQUE (chunk_id, node_id),
    -- A VERIFIED replica must carry the checksum it was verified against.
    CONSTRAINT ck_verified_has_checksum CHECK (state <> 'VERIFIED' OR stored_checksum IS NOT NULL)
);
CREATE INDEX ix_chunk_replicas_node_state ON chunk_replicas (node_id, state);
CREATE INDEX ix_chunk_replicas_chunk_state ON chunk_replicas (chunk_id, state);
CREATE INDEX ix_chunk_replicas_scrub ON chunk_replicas (last_verified_at NULLS FIRST) WHERE state = 'VERIFIED';

CREATE TABLE upload_sessions (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id           UUID         NOT NULL UNIQUE REFERENCES objects (id),
    owner_id            VARCHAR(64)  NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    -- SHA-256 of the canonical create request, used to reject key reuse with different parameters.
    request_fingerprint CHAR(64)     NOT NULL,
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'EXPIRED', 'ABORTED')),
    chunk_size          INTEGER      NOT NULL CHECK (chunk_size > 0),
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_upload_sessions_idempotency UNIQUE (owner_id, idempotency_key)
);
CREATE INDEX ix_upload_sessions_expiry ON upload_sessions (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE repair_jobs (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chunk_id        UUID        NOT NULL REFERENCES chunks (id),
    source_node_id  VARCHAR(32) REFERENCES storage_nodes (id),
    target_node_id  VARCHAR(32) REFERENCES storage_nodes (id),
    reason          VARCHAR(16) NOT NULL CHECK (reason IN ('NODE_OFFLINE', 'CORRUPTION', 'DRAIN', 'MANUAL')),
    state           VARCHAR(16) NOT NULL
                    CHECK (state IN ('PENDING', 'CLAIMED', 'COPYING', 'VERIFYING', 'COMMITTED', 'FAILED',
                                     'ABANDONED')),
    attempts        INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts    INTEGER     NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    last_error      TEXT,
    lease_owner     VARCHAR(64),
    lease_until     TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_repair_distinct_nodes CHECK (source_node_id IS NULL OR source_node_id <> target_node_id)
);
-- At most one active repair per chunk (ADR-0007).
CREATE UNIQUE INDEX uq_repair_jobs_active_chunk ON repair_jobs (chunk_id)
    WHERE state NOT IN ('COMMITTED', 'ABANDONED');
CREATE INDEX ix_repair_jobs_runnable ON repair_jobs (state, next_attempt_at);

CREATE TABLE outbox_events (
    id                UUID         PRIMARY KEY,
    aggregate_type    VARCHAR(32)  NOT NULL,
    aggregate_id      VARCHAR(128) NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    topic             VARCHAR(128) NOT NULL,
    message_key       VARCHAR(256) NOT NULL,
    payload           JSONB        NOT NULL,
    correlation_id    VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,
    publish_attempts  INTEGER      NOT NULL DEFAULT 0,
    last_error        TEXT
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- Consumer-side deduplication: insert in the same transaction as the side effect.
CREATE TABLE processed_events (
    consumer_group VARCHAR(64) NOT NULL,
    event_id       UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE TABLE audit_events (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    actor          VARCHAR(64)  NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    resource_type  VARCHAR(32)  NOT NULL,
    resource_id    VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(64),
    details        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_events_created ON audit_events (created_at DESC);
CREATE INDEX ix_audit_events_resource ON audit_events (resource_type, resource_id);

-- The audit log is append-only.
CREATE FUNCTION audit_events_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_append_only();
