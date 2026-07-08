CREATE TABLE data_sources (
  id            BIGSERIAL PRIMARY KEY,
  name          VARCHAR(120) NOT NULL UNIQUE,
  kind          VARCHAR(20)  NOT NULL,
  jdbc_url      VARCHAR(500) NOT NULL,
  username      VARCHAR(120),
  password      VARCHAR(255),
  role          VARCHAR(20)  NOT NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE classifications (
  id            BIGSERIAL PRIMARY KEY,
  data_source_id BIGINT NOT NULL REFERENCES data_sources(id) ON DELETE CASCADE,
  table_name    VARCHAR(200) NOT NULL,
  column_name   VARCHAR(200) NOT NULL,
  data_type     VARCHAR(80),
  pii_type      VARCHAR(60)  NOT NULL,
  confidence    DOUBLE PRECISION NOT NULL,
  suggested_function VARCHAR(60),
  status        VARCHAR(20) NOT NULL DEFAULT 'SUGGESTED',
  sample_value  VARCHAR(300),
  discovered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_class UNIQUE (data_source_id, table_name, column_name)
);

CREATE TABLE masking_policies (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(120) NOT NULL UNIQUE,
  description VARCHAR(500),
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE masking_rules (
  id          BIGSERIAL PRIMARY KEY,
  policy_id   BIGINT NOT NULL REFERENCES masking_policies(id) ON DELETE CASCADE,
  table_name  VARCHAR(200) NOT NULL,
  column_name VARCHAR(200) NOT NULL,
  function    VARCHAR(60)  NOT NULL,
  param1      VARCHAR(300),
  param2      VARCHAR(300),
  deterministic BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT uq_rule UNIQUE (policy_id, table_name, column_name)
);

CREATE TABLE provision_jobs (
  id           BIGSERIAL PRIMARY KEY,
  name         VARCHAR(200) NOT NULL,
  job_type     VARCHAR(30) NOT NULL,
  source_id    BIGINT REFERENCES data_sources(id),
  target_id    BIGINT REFERENCES data_sources(id),
  policy_id    BIGINT REFERENCES masking_policies(id),
  spec_json    TEXT,
  status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  rows_processed BIGINT NOT NULL DEFAULT 0,
  message      TEXT,
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at   TIMESTAMP,
  finished_at  TIMESTAMP
);

CREATE TABLE reservations (
  id            BIGSERIAL PRIMARY KEY,
  data_source_id BIGINT NOT NULL REFERENCES data_sources(id),
  table_name    VARCHAR(200) NOT NULL,
  criteria      VARCHAR(500),
  row_keys_json TEXT NOT NULL,
  reserved_by   VARCHAR(120) NOT NULL,
  purpose       VARCHAR(300),
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  expires_at    TIMESTAMP NOT NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE validation_reports (
  id          BIGSERIAL PRIMARY KEY,
  job_id      BIGINT,
  data_source_id BIGINT REFERENCES data_sources(id),
  policy_id   BIGINT,
  result      VARCHAR(20) NOT NULL,
  findings_json TEXT NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_events (
  id         BIGSERIAL PRIMARY KEY,
  actor      VARCHAR(120) NOT NULL,
  action     VARCHAR(120) NOT NULL,
  detail     TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
