CREATE TABLE be_sync_policies (
  id                   BIGSERIAL PRIMARY KEY,
  entity_id            BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  name                 VARCHAR(220) NOT NULL,
  sync_mode            VARCHAR(60) NOT NULL DEFAULT 'POLLING',
  status               VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  max_lag_seconds      INTEGER NOT NULL DEFAULT 900,
  schedule_cron        VARCHAR(120),
  sync_strategy        VARCHAR(80) NOT NULL DEFAULT 'FRESHNESS_CHECK',
  auto_refresh_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  notes                TEXT,
  created_by           VARCHAR(200),
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE be_sync_policy_members (
  id                    BIGSERIAL PRIMARY KEY,
  policy_id             BIGINT NOT NULL REFERENCES be_sync_policies(id) ON DELETE CASCADE,
  entity_id             BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  member_id             BIGINT REFERENCES business_entity_members(id) ON DELETE SET NULL,
  system_name           VARCHAR(160),
  data_source_id        BIGINT,
  schema_name           VARCHAR(160),
  table_name            VARCHAR(240) NOT NULL,
  logical_role          VARCHAR(120),
  key_columns           TEXT,
  watermark_column      VARCHAR(240),
  max_lag_seconds       INTEGER,
  sync_mode             VARCHAR(60) NOT NULL DEFAULT 'POLLING',
  query_filter          TEXT,
  last_source_watermark VARCHAR(240),
  last_checked_at       TIMESTAMP,
  last_status           VARCHAR(40) NOT NULL DEFAULT 'NEVER_CHECKED',
  last_message          TEXT,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE be_sync_runs (
  id            BIGSERIAL PRIMARY KEY,
  entity_id     BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  policy_id     BIGINT NOT NULL REFERENCES be_sync_policies(id) ON DELETE CASCADE,
  run_type      VARCHAR(60) NOT NULL DEFAULT 'FRESHNESS_CHECK',
  status        VARCHAR(40) NOT NULL,
  result_json   TEXT NOT NULL,
  created_by    VARCHAR(200),
  started_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at  TIMESTAMP
);

CREATE INDEX idx_be_sync_policy_entity ON be_sync_policies(entity_id, updated_at);
CREATE INDEX idx_be_sync_policy_member_policy ON be_sync_policy_members(policy_id, last_status);
CREATE INDEX idx_be_sync_run_policy ON be_sync_runs(policy_id, started_at);
CREATE INDEX idx_be_sync_run_entity ON be_sync_runs(entity_id, started_at);
