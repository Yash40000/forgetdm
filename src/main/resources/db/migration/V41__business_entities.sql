CREATE TABLE business_entities (
  id                   BIGSERIAL PRIMARY KEY,
  name                 VARCHAR(200) NOT NULL UNIQUE,
  description          TEXT,
  domain               VARCHAR(120),
  owner_username       VARCHAR(200),
  primary_dataset_id   BIGINT REFERENCES dataset_definitions(id) ON DELETE SET NULL,
  root_table           VARCHAR(300),
  business_key_columns TEXT,
  status               VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE business_entity_members (
  id                   BIGSERIAL PRIMARY KEY,
  entity_id            BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  system_name          VARCHAR(200),
  data_source_id       BIGINT REFERENCES data_sources(id) ON DELETE SET NULL,
  schema_name          VARCHAR(200),
  dataset_id           BIGINT REFERENCES dataset_definitions(id) ON DELETE SET NULL,
  logical_role         VARCHAR(120) NOT NULL,
  table_name           VARCHAR(300) NOT NULL,
  table_alias          VARCHAR(300),
  key_columns          TEXT,
  join_to_role         VARCHAR(120),
  relationship_json    TEXT,
  include_in_subset    BOOLEAN NOT NULL DEFAULT TRUE,
  include_in_synthetic BOOLEAN NOT NULL DEFAULT TRUE,
  ordinal_no           INTEGER NOT NULL DEFAULT 0,
  CONSTRAINT uq_business_entity_member UNIQUE (entity_id, logical_role, table_name)
);

CREATE INDEX idx_business_entity_members_entity ON business_entity_members(entity_id);
CREATE INDEX idx_business_entity_members_dataset ON business_entity_members(dataset_id);
CREATE INDEX idx_business_entity_members_source ON business_entity_members(data_source_id);
