-- Phase 4: read models built by Kafka consumers, and the dead-letter record store.
-- These are projections: rebuildable from Kafka/PostgreSQL, never authoritative.

-- Event timeline (consumer group bv-timeline). One row per event_id, however often delivered.
CREATE TABLE event_log (
    event_id          UUID         PRIMARY KEY,
    event_type        VARCHAR(64)  NOT NULL,
    aggregate_type    VARCHAR(32)  NOT NULL,
    aggregate_id      VARCHAR(128) NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    correlation_id    VARCHAR(64),
    occurred_at       TIMESTAMPTZ  NOT NULL,
    topic             VARCHAR(128) NOT NULL,
    kafka_partition   INTEGER      NOT NULL,
    kafka_offset      BIGINT       NOT NULL,
    payload           JSONB        NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_event_log_occurred ON event_log (occurred_at DESC);
CREATE INDEX ix_event_log_aggregate ON event_log (aggregate_id, occurred_at DESC);

-- Object statistics projection (consumer group bv-object-stats). Single row.
-- stored_* counts objects that are COMPLETE or DEGRADED; degraded_objects is a subset.
CREATE TABLE object_stats (
    id               SMALLINT    PRIMARY KEY CHECK (id = 1),
    stored_objects   BIGINT      NOT NULL DEFAULT 0,
    stored_bytes     BIGINT      NOT NULL DEFAULT 0,
    degraded_objects BIGINT      NOT NULL DEFAULT 0,
    deleted_objects  BIGINT      NOT NULL DEFAULT 0,
    events_applied   BIGINT      NOT NULL DEFAULT 0,
    last_event_at    TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO object_stats (id) VALUES (1);

-- Highest aggregate version each consumer group has applied, to ignore stale events.
CREATE TABLE projection_versions (
    consumer_group VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(128) NOT NULL,
    version        BIGINT       NOT NULL,
    PRIMARY KEY (consumer_group, aggregate_id)
);

-- Records read from bytevault.storage-dlq (consumer group bv-dlq-recorder).
CREATE TABLE dlq_events (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    dlq_partition      INTEGER      NOT NULL,
    dlq_offset         BIGINT       NOT NULL,
    event_id           UUID,
    event_type         VARCHAR(64),
    original_topic     VARCHAR(128) NOT NULL,
    original_key       VARCHAR(256),
    original_partition INTEGER,
    original_offset    BIGINT,
    consumer_group     VARCHAR(64),
    failure_reason     TEXT,
    attempts           INTEGER,
    failed_at          TIMESTAMPTZ  NOT NULL,
    correlation_id     VARCHAR(64),
    payload            TEXT         NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'REPLAYED')),
    replay_count       INTEGER      NOT NULL DEFAULT 0,
    last_replayed_at   TIMESTAMPTZ,
    last_replayed_by   VARCHAR(64),
    recorded_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_dlq_events_position UNIQUE (dlq_partition, dlq_offset)
);
CREATE INDEX ix_dlq_events_status ON dlq_events (status, recorded_at DESC);
CREATE INDEX ix_dlq_events_event ON dlq_events (event_id);
