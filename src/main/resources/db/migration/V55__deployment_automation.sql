CREATE TABLE forge_scheduler_leases (
  lease_name       VARCHAR(160) PRIMARY KEY,
  owner_id         VARCHAR(160) NOT NULL,
  lease_until      TIMESTAMP NOT NULL,
  updated_at       TIMESTAMP NOT NULL
);

CREATE TABLE forge_api_tokens (
  id               VARCHAR(36) PRIMARY KEY,
  user_id          BIGINT NOT NULL REFERENCES forge_users(id) ON DELETE CASCADE,
  name             VARCHAR(160) NOT NULL,
  token_hash       VARCHAR(120) NOT NULL UNIQUE,
  token_prefix     VARCHAR(24) NOT NULL,
  created_at       TIMESTAMP NOT NULL,
  expires_at       TIMESTAMP,
  last_used_at     TIMESTAMP,
  revoked_at       TIMESTAMP,
  UNIQUE(user_id, name)
);
CREATE INDEX idx_forge_api_tokens_user ON forge_api_tokens(user_id, created_at DESC);

ALTER TABLE datascope_saved_jobs ADD COLUMN self_service_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE datascope_saved_jobs ADD COLUMN self_service_label VARCHAR(200);

CREATE TABLE self_service_requests (
  id               VARCHAR(36) PRIMARY KEY,
  template_id      VARCHAR(36) NOT NULL REFERENCES datascope_saved_jobs(id),
  requested_by_id  BIGINT NOT NULL REFERENCES forge_users(id),
  requested_by     VARCHAR(120) NOT NULL,
  purpose          VARCHAR(1000) NOT NULL,
  environment      VARCHAR(80),
  status           VARCHAR(40) NOT NULL,
  decision_by_id   BIGINT REFERENCES forge_users(id),
  decision_by      VARCHAR(120),
  decision_note    VARCHAR(1000),
  run_id           BIGINT REFERENCES provision_jobs(id),
  created_at       TIMESTAMP NOT NULL,
  decided_at       TIMESTAMP,
  fulfilled_at     TIMESTAMP,
  updated_at       TIMESTAMP NOT NULL
);
CREATE INDEX idx_self_service_requester ON self_service_requests(requested_by_id, created_at DESC);
CREATE INDEX idx_self_service_status ON self_service_requests(status, created_at);

CREATE TABLE integration_endpoints (
  id               VARCHAR(36) PRIMARY KEY,
  name             VARCHAR(160) NOT NULL UNIQUE,
  kind             VARCHAR(40) NOT NULL,
  url              VARCHAR(2000) NOT NULL,
  event_types      VARCHAR(2000),
  secret_env       VARCHAR(160),
  enabled          BOOLEAN NOT NULL DEFAULT TRUE,
  created_by       VARCHAR(120),
  created_at       TIMESTAMP NOT NULL,
  updated_at       TIMESTAMP NOT NULL
);

CREATE TABLE integration_outbox (
  id               VARCHAR(36) PRIMARY KEY,
  endpoint_id      VARCHAR(36) NOT NULL REFERENCES integration_endpoints(id) ON DELETE CASCADE,
  event_type       VARCHAR(120) NOT NULL,
  payload_json     TEXT NOT NULL,
  status           VARCHAR(30) NOT NULL,
  attempts         INTEGER NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMP NOT NULL,
  delivered_at     TIMESTAMP,
  last_error       VARCHAR(2000),
  created_at       TIMESTAMP NOT NULL,
  updated_at       TIMESTAMP NOT NULL
);
CREATE INDEX idx_integration_outbox_due ON integration_outbox(status, next_attempt_at);
