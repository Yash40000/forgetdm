-- Tool-level Referential Integrity & Keys registry.
-- PK and FK definitions live independently of any single feature (masking, synthetic, subsetting)
-- and are scoped to a user, a group, or globally so they are NOT universal for all users.
-- Resolution precedence (applied by the service): user > group > global > live DB metadata.

CREATE TABLE ri_primary_key (
  id             BIGSERIAL PRIMARY KEY,
  name           VARCHAR(160),
  description    VARCHAR(500),
  visibility     VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',   -- PRIVATE | GROUP | GLOBAL
  owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE CASCADE,
  owner_username VARCHAR(120),
  owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE CASCADE,
  data_source_id BIGINT NOT NULL,
  schema_name    VARCHAR(200),
  table_name     VARCHAR(200) NOT NULL,
  key_columns    VARCHAR(1000) NOT NULL,                   -- ordered, comma-separated
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP
);

CREATE TABLE ri_relationship (
  id                BIGSERIAL PRIMARY KEY,
  name              VARCHAR(160),
  description       VARCHAR(500),
  visibility        VARCHAR(16) NOT NULL DEFAULT 'PRIVATE', -- PRIVATE | GROUP | GLOBAL
  owner_user_id     BIGINT REFERENCES forge_users(id)  ON DELETE CASCADE,
  owner_username    VARCHAR(120),
  owner_group_id    BIGINT REFERENCES forge_groups(id) ON DELETE CASCADE,
  data_source_id    BIGINT NOT NULL,
  child_schema      VARCHAR(200),
  child_table       VARCHAR(200) NOT NULL,
  child_columns     VARCHAR(1000) NOT NULL,                 -- ordered, comma-separated
  parent_schema     VARCHAR(200),
  parent_table      VARCHAR(200) NOT NULL,
  parent_columns    VARCHAR(1000) NOT NULL,                 -- ordered, comma-separated, aligned with child_columns
  relationship_type VARCHAR(30) NOT NULL DEFAULT 'NON_IDENTIFYING', -- IDENTIFYING | NON_IDENTIFYING | OPTIONAL
  cardinality_min   INTEGER,                                -- children-per-parent (consumed by e.g. synthetic generation)
  cardinality_max   INTEGER,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP,
  CONSTRAINT ck_ri_rel_visibility CHECK (visibility IN ('PRIVATE','GROUP','GLOBAL'))
);

ALTER TABLE ri_primary_key
  ADD CONSTRAINT ck_ri_pk_visibility CHECK (visibility IN ('PRIVATE','GROUP','GLOBAL'));

CREATE INDEX idx_ri_pk_lookup  ON ri_primary_key (data_source_id, schema_name, table_name);
CREATE INDEX idx_ri_pk_owner   ON ri_primary_key (visibility, owner_user_id, owner_group_id);
CREATE INDEX idx_ri_rel_lookup ON ri_relationship (data_source_id, child_schema, child_table);
CREATE INDEX idx_ri_rel_parent ON ri_relationship (data_source_id, parent_schema, parent_table);
CREATE INDEX idx_ri_rel_owner  ON ri_relationship (visibility, owner_user_id, owner_group_id);
