-- K2View-style logical Micro-Database / Entity Capsule layer.
-- Persists each business entity INSTANCE (e.g. Customer 360 / CUST-10025) as a governed,
-- reusable store: canonical identity, per-member fragments, version history, watermark
-- evidence, access grants, and a lineage trail. Shared physical tables — NOT a database
-- per customer/account.

CREATE TABLE be_entity_instances (
  id                    BIGSERIAL PRIMARY KEY,
  entity_id             BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  canonical_key         VARCHAR(400) NOT NULL,
  business_key_json     TEXT NOT NULL,
  status                VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  policy_id             BIGINT,
  current_version       INTEGER NOT NULL DEFAULT 0,
  fragment_count        INTEGER NOT NULL DEFAULT 0,
  total_rows            BIGINT NOT NULL DEFAULT 0,
  last_materialized_at  TIMESTAMP,
  last_materialized_by  VARCHAR(200),
  notes                 TEXT,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(entity_id, canonical_key)
);

CREATE TABLE be_entity_fragments (
  id              BIGSERIAL PRIMARY KEY,
  instance_id     BIGINT NOT NULL REFERENCES be_entity_instances(id) ON DELETE CASCADE,
  member_id       BIGINT REFERENCES business_entity_members(id) ON DELETE SET NULL,
  system_name     VARCHAR(160),
  data_source_id  BIGINT,
  schema_name     VARCHAR(160),
  table_name      VARCHAR(240) NOT NULL,
  key_columns     TEXT,
  fragment_type   VARCHAR(40) NOT NULL DEFAULT 'RAW_REF',
  status          VARCHAR(20) NOT NULL DEFAULT 'CURRENT',
  row_count       INTEGER NOT NULL DEFAULT 0,
  payload_json    TEXT,
  content_hash    VARCHAR(128),
  message         TEXT,
  captured_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE be_entity_versions (
  id              BIGSERIAL PRIMARY KEY,
  instance_id     BIGINT NOT NULL REFERENCES be_entity_instances(id) ON DELETE CASCADE,
  version_no      INTEGER NOT NULL,
  kind            VARCHAR(40) NOT NULL DEFAULT 'RAW_REF_ONLY',
  policy_id       BIGINT,
  fragment_count  INTEGER NOT NULL DEFAULT 0,
  total_rows      BIGINT NOT NULL DEFAULT 0,
  content_hash    VARCHAR(128),
  notes           TEXT,
  created_by      VARCHAR(200),
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(instance_id, version_no)
);

CREATE TABLE be_entity_watermarks (
  id                BIGSERIAL PRIMARY KEY,
  instance_id       BIGINT NOT NULL REFERENCES be_entity_instances(id) ON DELETE CASCADE,
  member_id         BIGINT REFERENCES business_entity_members(id) ON DELETE SET NULL,
  table_name        VARCHAR(240),
  watermark_column  VARCHAR(160),
  watermark_value   VARCHAR(200),
  status            VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
  message           TEXT,
  source            VARCHAR(40) NOT NULL DEFAULT 'CAPTURE',
  checked_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(instance_id, member_id)
);

CREATE TABLE be_entity_access_grants (
  id             BIGSERIAL PRIMARY KEY,
  entity_id      BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  instance_id    BIGINT REFERENCES be_entity_instances(id) ON DELETE CASCADE,
  grantee_type   VARCHAR(40) NOT NULL DEFAULT 'USER',
  grantee        VARCHAR(200) NOT NULL,
  scope          VARCHAR(40) NOT NULL DEFAULT 'READ',
  granted_by     VARCHAR(200),
  granted_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at     TIMESTAMP,
  revoked        BOOLEAN NOT NULL DEFAULT FALSE,
  revoked_at     TIMESTAMP,
  revoked_by     VARCHAR(200),
  note           TEXT
);

CREATE TABLE be_entity_lineage_events (
  id             BIGSERIAL PRIMARY KEY,
  instance_id    BIGINT NOT NULL REFERENCES be_entity_instances(id) ON DELETE CASCADE,
  event_type     VARCHAR(60) NOT NULL,
  detail_json    TEXT,
  actor          VARCHAR(200),
  occurred_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_be_entity_instances_entity ON be_entity_instances(entity_id, status);
CREATE INDEX idx_be_entity_fragments_instance ON be_entity_fragments(instance_id, status);
CREATE INDEX idx_be_entity_fragments_member ON be_entity_fragments(member_id);
CREATE INDEX idx_be_entity_versions_instance ON be_entity_versions(instance_id);
CREATE INDEX idx_be_entity_watermarks_instance ON be_entity_watermarks(instance_id);
CREATE INDEX idx_be_entity_access_grants_entity ON be_entity_access_grants(entity_id, revoked);
CREATE INDEX idx_be_entity_access_grants_instance ON be_entity_access_grants(instance_id, revoked);
CREATE INDEX idx_be_entity_lineage_instance ON be_entity_lineage_events(instance_id, occurred_at);
