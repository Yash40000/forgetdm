-- User/group-scoped custom PII detection patterns.
-- Analysts can add their own column-name or value regexes for any PII type (and optionally override the
-- suggested masking function), scoped Private / Group / Global. During a scan these overlay the built-in
-- patterns with precedence user > group > global. NAME patterns match column names; VALUE patterns match
-- sampled values.

CREATE TABLE pii_pattern (
  id                 BIGSERIAL PRIMARY KEY,
  pii_type           VARCHAR(60)  NOT NULL,
  kind               VARCHAR(10)  NOT NULL,            -- NAME | VALUE
  regex              VARCHAR(1000) NOT NULL,
  suggested_function VARCHAR(60),                       -- optional: override the suggested masker for this type
  description        VARCHAR(500),
  visibility         VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE',  -- PRIVATE | GROUP | GLOBAL
  owner_user_id      BIGINT REFERENCES forge_users(id)  ON DELETE CASCADE,
  owner_username     VARCHAR(120),
  owner_group_id     BIGINT REFERENCES forge_groups(id) ON DELETE CASCADE,
  created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP,
  CONSTRAINT ck_pii_pattern_kind CHECK (kind IN ('NAME','VALUE')),
  CONSTRAINT ck_pii_pattern_visibility CHECK (visibility IN ('PRIVATE','GROUP','GLOBAL'))
);

CREATE INDEX idx_pii_pattern_owner ON pii_pattern (visibility, owner_user_id, owner_group_id);
CREATE INDEX idx_pii_pattern_type  ON pii_pattern (pii_type, kind);
