-- V72 — Coordinated multi-source extraction (RFP §3.1.1 "Timeflow Synchronization")
--
-- A Sync Set groups several data sources so they can be snapshotted together as one coordinated
-- run: each member is pinned to its transaction-log position (Postgres LSN / Oracle SCN) at a common
-- coordination instant, then extracted in parallel using a lock-free consistent read (MVCC), all
-- within a bounded batch window. Combined with CDC, the pinned positions let every member be aligned
-- to a single sync point downstream.
--
-- sync_set          — the named group (owner/visibility governed like other core objects).
-- sync_set_member   — a source (+ schema) in the group.
-- sync_run          — one coordinated snapshot run (window timing, aggregate status).
-- sync_run_member   — per-source result: snapshot id, consistency point, rows, status.

CREATE TABLE sync_set (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    description    TEXT,
    owner_user_id  BIGINT,
    owner_username VARCHAR(128),
    owner_group_id BIGINT,
    visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sync_set_name UNIQUE (name)
);

CREATE TABLE sync_set_member (
    id             BIGSERIAL PRIMARY KEY,
    sync_set_id    BIGINT NOT NULL REFERENCES sync_set(id) ON DELETE CASCADE,
    data_source_id BIGINT NOT NULL,
    schema_name    VARCHAR(128),
    CONSTRAINT uq_sync_member UNIQUE (sync_set_id, data_source_id)
);

CREATE TABLE sync_run (
    id              BIGSERIAL PRIMARY KEY,
    sync_set_id     BIGINT NOT NULL REFERENCES sync_set(id) ON DELETE CASCADE,
    status          VARCHAR(16) NOT NULL,          -- RUNNING | SUCCESS | PARTIAL | FAILED
    target_ts       TIMESTAMPTZ,                   -- coordination instant the log positions were pinned at
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    window_ms       BIGINT,
    member_count    INT NOT NULL DEFAULT 0,
    succeeded_count INT NOT NULL DEFAULT 0,
    note            TEXT
);

CREATE TABLE sync_run_member (
    id                BIGSERIAL PRIMARY KEY,
    sync_run_id       BIGINT NOT NULL REFERENCES sync_run(id) ON DELETE CASCADE,
    data_source_id    BIGINT NOT NULL,
    data_source_name  VARCHAR(200),
    schema_name       VARCHAR(128),
    snapshot_id       BIGINT,
    consistency_point VARCHAR(64),                 -- LSN / SCN pinned at target_ts
    mechanism         VARCHAR(64),                 -- e.g. PostgreSQL logical replication / Oracle LogMiner
    row_count         BIGINT,
    status            VARCHAR(16) NOT NULL,         -- SUCCESS | FAILED
    error             TEXT,
    elapsed_ms        BIGINT
);

CREATE INDEX idx_sync_run_set        ON sync_run (sync_set_id, started_at DESC);
CREATE INDEX idx_sync_run_member_run ON sync_run_member (sync_run_id);
