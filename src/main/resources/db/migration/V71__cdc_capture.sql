-- V71 — Log-based Change Data Capture (true CDC)
--
-- ForgeTDM's TimeFlow engine already does *snapshot* incremental sync: it re-reads a
-- source table in PK order, chunks it, and content-addresses the chunks so only changed
-- blocks are physically stored. That still requires a full table read every cycle.
--
-- This migration backs *true* CDC: reading the database transaction log (Postgres logical
-- replication / WAL) so only rows actually altered since the last checkpoint are captured —
-- no full rescan. Captured changes are buffered here and later applied to produce an
-- incremental TimeFlow snapshot.
--
-- cdc_capture  — one row per data source; owns the replication slot + LSN checkpoint.
-- cdc_change   — the append-only buffer of decoded row changes (the change feed).
--
-- Tenancy columns mirror the V61 core-object convention (owner + visibility) so a capture
-- is governed exactly like the data source it reads.

CREATE TABLE cdc_capture (
    id                 BIGSERIAL PRIMARY KEY,
    data_source_id     BIGINT      NOT NULL,
    slot_name          VARCHAR(64) NOT NULL,
    plugin             VARCHAR(32) NOT NULL DEFAULT 'test_decoding',
    publication_name   VARCHAR(64),
    schema_name        VARCHAR(128),
    tables_json        TEXT,                       -- JSON array of "schema.table"; NULL = all user tables
    status             VARCHAR(16) NOT NULL DEFAULT 'INACTIVE',  -- INACTIVE | ACTIVE | ERROR
    confirmed_lsn      VARCHAR(32),                -- last LSN we flushed/confirmed to the server
    restart_lsn        VARCHAR(32),                -- slot restart_lsn at creation
    rows_captured      BIGINT      NOT NULL DEFAULT 0,
    last_error         TEXT,
    last_polled_at     TIMESTAMPTZ,
    owner_user_id      BIGINT,
    owner_username     VARCHAR(128),
    owner_group_id     BIGINT,
    visibility         VARCHAR(16) NOT NULL DEFAULT 'GROUP',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cdc_capture_data_source UNIQUE (data_source_id),
    CONSTRAINT uq_cdc_capture_slot        UNIQUE (slot_name)
);

CREATE TABLE cdc_change (
    id             BIGSERIAL PRIMARY KEY,
    capture_id     BIGINT      NOT NULL REFERENCES cdc_capture(id) ON DELETE CASCADE,
    data_source_id BIGINT      NOT NULL,
    lsn            VARCHAR(32),
    xid            BIGINT,
    schema_name    VARCHAR(128),
    table_name     VARCHAR(128) NOT NULL,
    op             CHAR(1)     NOT NULL,           -- I (insert) | U (update) | D (delete)
    pk_json        TEXT,                           -- JSON object of primary-key columns (best-effort)
    change_json    TEXT,                           -- JSON object: column name -> new value (or old for delete)
    captured_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cdc_change_ds_id     ON cdc_change (data_source_id, id);
CREATE INDEX idx_cdc_change_capture   ON cdc_change (capture_id, id);
CREATE INDEX idx_cdc_change_table     ON cdc_change (data_source_id, schema_name, table_name);
