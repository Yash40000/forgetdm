CREATE TABLE be_issue_packages (
  id                    BIGSERIAL PRIMARY KEY,
  entity_id             BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  issue_key             VARCHAR(160) NOT NULL,
  title                 VARCHAR(300) NOT NULL,
  severity              VARCHAR(40),
  source_environment    VARCHAR(120),
  target_environment    VARCHAR(120),
  snapshot_id           BIGINT REFERENCES business_entity_snapshots(id) ON DELETE SET NULL,
  reservation_id        BIGINT REFERENCES business_entity_reservations(id) ON DELETE SET NULL,
  recreation_mode       VARCHAR(60) NOT NULL DEFAULT 'MASKED_SUBSET',
  privacy_action        VARCHAR(60) NOT NULL DEFAULT 'MASK_OR_SYNTHETIC',
  status                VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
  business_keys_json    TEXT NOT NULL,
  package_manifest_json TEXT NOT NULL,
  replay_instructions   TEXT,
  approval_status       VARCHAR(40) NOT NULL DEFAULT 'NOT_REQUESTED',
  created_by            VARCHAR(200),
  approved_by           VARCHAR(200),
  expires_at            TIMESTAMP,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE be_lookalike_profiles (
  id                  BIGSERIAL PRIMARY KEY,
  entity_id           BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  name                VARCHAR(220) NOT NULL,
  objective           TEXT,
  privacy_mode        VARCHAR(80) NOT NULL DEFAULT 'NO_RAW_VALUES',
  sample_policy       VARCHAR(80) NOT NULL DEFAULT 'METADATA_ONLY',
  row_goal            BIGINT NOT NULL DEFAULT 1000,
  generator_plan_json TEXT NOT NULL,
  safety_report_json  TEXT NOT NULL,
  status              VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
  created_by          VARCHAR(200),
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE be_catalog_assets (
  id                   BIGSERIAL PRIMARY KEY,
  entity_id            BIGINT REFERENCES business_entities(id) ON DELETE CASCADE,
  asset_type           VARCHAR(80) NOT NULL,
  asset_id             BIGINT,
  qualified_name       VARCHAR(500) NOT NULL UNIQUE,
  display_name         VARCHAR(300) NOT NULL,
  owner_username       VARCHAR(200),
  domain               VARCHAR(120),
  tags                 TEXT,
  certification_status VARCHAR(60) NOT NULL DEFAULT 'UNCERTIFIED',
  lineage_json         TEXT,
  dependencies_json    TEXT,
  quality_score        DOUBLE PRECISION NOT NULL DEFAULT 0,
  status               VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE be_governance_requests (
  id                BIGSERIAL PRIMARY KEY,
  entity_id         BIGINT REFERENCES business_entities(id) ON DELETE CASCADE,
  object_type       VARCHAR(80) NOT NULL,
  object_id         BIGINT,
  action            VARCHAR(120) NOT NULL,
  requested_by      VARCHAR(200) NOT NULL,
  reviewer          VARCHAR(200),
  status            VARCHAR(40) NOT NULL DEFAULT 'PENDING',
  risk_level        VARCHAR(40) NOT NULL DEFAULT 'MEDIUM',
  risk_json         TEXT,
  evidence_json     TEXT,
  comments          TEXT,
  signed_by         VARCHAR(200),
  signed_at         TIMESTAMP,
  e_signature_hash  VARCHAR(200),
  due_at            TIMESTAMP,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE be_entity_execution_plans (
  id                   BIGSERIAL PRIMARY KEY,
  entity_id            BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  name                 VARCHAR(220) NOT NULL,
  operation_type       VARCHAR(80) NOT NULL,
  source_environment   VARCHAR(120),
  target_environment   VARCHAR(120),
  mode                 VARCHAR(80) NOT NULL DEFAULT 'PLAN_ONLY',
  status               VARCHAR(40) NOT NULL DEFAULT 'READY_FOR_APPROVAL',
  issue_package_id     BIGINT REFERENCES be_issue_packages(id) ON DELETE SET NULL,
  lookalike_profile_id BIGINT REFERENCES be_lookalike_profiles(id) ON DELETE SET NULL,
  approved_request_id  BIGINT REFERENCES be_governance_requests(id) ON DELETE SET NULL,
  plan_json            TEXT NOT NULL,
  validation_json      TEXT,
  loader_strategy_json TEXT,
  created_by           VARCHAR(200),
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE be_operational_packages (
  id                 BIGSERIAL PRIMARY KEY,
  entity_id          BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  execution_plan_id  BIGINT REFERENCES be_entity_execution_plans(id) ON DELETE CASCADE,
  package_type       VARCHAR(80) NOT NULL DEFAULT 'SCHEDULER_RUNNER',
  name               VARCHAR(220) NOT NULL,
  status             VARCHAR(40) NOT NULL DEFAULT 'READY',
  manifest_json      TEXT NOT NULL,
  shell_script       TEXT NOT NULL,
  health_check_json  TEXT,
  promotion_json     TEXT,
  created_by         VARCHAR(200),
  created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_be_issue_entity ON be_issue_packages(entity_id, created_at);
CREATE INDEX idx_be_lookalike_entity ON be_lookalike_profiles(entity_id, created_at);
CREATE INDEX idx_be_catalog_entity ON be_catalog_assets(entity_id, asset_type);
CREATE INDEX idx_be_governance_entity ON be_governance_requests(entity_id, status);
CREATE INDEX idx_be_exec_entity ON be_entity_execution_plans(entity_id, created_at);
CREATE INDEX idx_be_ops_pkg_entity ON be_operational_packages(entity_id, created_at);
