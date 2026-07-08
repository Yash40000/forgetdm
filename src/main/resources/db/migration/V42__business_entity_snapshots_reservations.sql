CREATE TABLE business_entity_snapshots (
  id                         BIGSERIAL PRIMARY KEY,
  entity_id                  BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  name                       VARCHAR(220) NOT NULL,
  snapshot_type              VARCHAR(60) NOT NULL DEFAULT 'ENTITY_BOOKMARK',
  capture_mode               VARCHAR(60) NOT NULL DEFAULT 'EVIDENCE_ONLY',
  status                     VARCHAR(40) NOT NULL DEFAULT 'AVAILABLE',
  consistency_id             VARCHAR(100) NOT NULL,
  created_by                 VARCHAR(200),
  note                       TEXT,
  retention_until            TIMESTAMP,
  immutable                  BOOLEAN NOT NULL DEFAULT TRUE,
  entity_json                TEXT NOT NULL,
  member_manifest_json       TEXT NOT NULL,
  rollback_plan_json         TEXT,
  total_members              INTEGER NOT NULL DEFAULT 0,
  linked_virtual_snapshots   INTEGER NOT NULL DEFAULT 0,
  total_rows                 BIGINT NOT NULL DEFAULT 0,
  created_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at               TIMESTAMP
);

CREATE TABLE business_entity_snapshot_members (
  id                         BIGSERIAL PRIMARY KEY,
  snapshot_id                BIGINT NOT NULL REFERENCES business_entity_snapshots(id) ON DELETE CASCADE,
  entity_member_id           BIGINT REFERENCES business_entity_members(id) ON DELETE SET NULL,
  data_source_id             BIGINT REFERENCES data_sources(id) ON DELETE SET NULL,
  schema_name                VARCHAR(200),
  table_name                 VARCHAR(300) NOT NULL,
  key_columns                TEXT,
  row_keys_json              TEXT,
  criteria                   TEXT,
  virtual_snapshot_id        BIGINT REFERENCES virtual_snapshots(id) ON DELETE SET NULL,
  row_count                  BIGINT NOT NULL DEFAULT 0,
  status                     VARCHAR(40) NOT NULL DEFAULT 'CAPTURED',
  evidence_json              TEXT
);

CREATE TABLE business_entity_reservations (
  id                         BIGSERIAL PRIMARY KEY,
  entity_id                  BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  snapshot_id                BIGINT REFERENCES business_entity_snapshots(id) ON DELETE SET NULL,
  name                       VARCHAR(220),
  reserved_by                VARCHAR(200) NOT NULL,
  owner_group                VARCHAR(200),
  purpose                    TEXT,
  environment                VARCHAR(120),
  criteria                   TEXT,
  requested_count            INTEGER NOT NULL DEFAULT 1,
  business_key_values_json   TEXT NOT NULL,
  status                     VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  conflict_policy            VARCHAR(40) NOT NULL DEFAULT 'BLOCK',
  expires_at                 TIMESTAMP NOT NULL,
  created_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  released_at                TIMESTAMP
);

CREATE TABLE business_entity_reservation_members (
  id                         BIGSERIAL PRIMARY KEY,
  reservation_id             BIGINT NOT NULL REFERENCES business_entity_reservations(id) ON DELETE CASCADE,
  entity_member_id           BIGINT REFERENCES business_entity_members(id) ON DELETE SET NULL,
  data_source_id             BIGINT REFERENCES data_sources(id) ON DELETE SET NULL,
  schema_name                VARCHAR(200),
  table_name                 VARCHAR(300) NOT NULL,
  key_columns                TEXT,
  row_keys_json              TEXT NOT NULL,
  criteria                   TEXT,
  row_count                  BIGINT NOT NULL DEFAULT 0,
  status                     VARCHAR(40) NOT NULL DEFAULT 'RESERVED'
);

CREATE INDEX idx_be_snapshots_entity ON business_entity_snapshots(entity_id, created_at);
CREATE INDEX idx_be_snapshot_members_snapshot ON business_entity_snapshot_members(snapshot_id);
CREATE INDEX idx_be_reservations_entity_status ON business_entity_reservations(entity_id, status);
CREATE INDEX idx_be_reservation_members_reservation ON business_entity_reservation_members(reservation_id);
