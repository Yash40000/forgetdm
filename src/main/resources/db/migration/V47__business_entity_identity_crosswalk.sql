CREATE TABLE be_identity_subjects (
  id              BIGSERIAL PRIMARY KEY,
  entity_id       BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  canonical_key   VARCHAR(240) NOT NULL,
  identity_type   VARCHAR(80) NOT NULL DEFAULT 'BUSINESS_ENTITY',
  status          VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  confidence      DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  attributes_json TEXT NOT NULL DEFAULT '{}',
  created_by      VARCHAR(200),
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(entity_id, canonical_key)
);

CREATE TABLE be_identity_links (
  id                BIGSERIAL PRIMARY KEY,
  entity_id         BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  subject_id        BIGINT NOT NULL REFERENCES be_identity_subjects(id) ON DELETE CASCADE,
  member_id         BIGINT REFERENCES business_entity_members(id) ON DELETE SET NULL,
  system_name       VARCHAR(160),
  data_source_id    BIGINT,
  schema_name       VARCHAR(160),
  table_name        VARCHAR(240) NOT NULL,
  logical_role      VARCHAR(120),
  key_columns       TEXT,
  key_values_json   TEXT NOT NULL,
  external_id       VARCHAR(500) NOT NULL,
  identity_key_hash VARCHAR(128) NOT NULL,
  confidence        DOUBLE PRECISION NOT NULL DEFAULT 1.0,
  match_rule        VARCHAR(160),
  status            VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  source            VARCHAR(120),
  created_by        VARCHAR(200),
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(entity_id, identity_key_hash)
);

CREATE INDEX idx_be_identity_subject_entity ON be_identity_subjects(entity_id, canonical_key);
CREATE INDEX idx_be_identity_link_subject ON be_identity_links(subject_id, status);
CREATE INDEX idx_be_identity_link_lookup ON be_identity_links(entity_id, identity_key_hash);
CREATE INDEX idx_be_identity_link_member ON be_identity_links(entity_id, member_id);
