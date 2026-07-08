-- Mainframe file-masking pipeline: connections (LPARs), copybook registry, field masks, batch jobs.

CREATE TABLE mf_connections (
  id                  BIGSERIAL PRIMARY KEY,
  name                VARCHAR(120) NOT NULL UNIQUE,
  type                VARCHAR(20)  NOT NULL,            -- LOCAL | ZOWE
  host                VARCHAR(200),
  port                INTEGER,
  base_path           VARCHAR(200),                     -- z/OSMF base path (default /zosmf)
  username            VARCHAR(120),
  password            VARCHAR(255),
  base_dir            VARCHAR(500),                     -- LOCAL landing-folder path
  code_page           VARCHAR(40)  NOT NULL DEFAULT 'Cp037',
  trust_all_certs     BOOLEAN NOT NULL DEFAULT FALSE,   -- accept self-signed z/OSMF cert
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE mf_copybooks (
  id            BIGSERIAL PRIMARY KEY,
  name          VARCHAR(120) NOT NULL UNIQUE,
  source        TEXT NOT NULL,
  code_page     VARCHAR(40) NOT NULL DEFAULT 'Cp037',
  record_name   VARCHAR(120),
  record_length INTEGER,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE mf_copybook_masks (
  id          BIGSERIAL PRIMARY KEY,
  copybook_id BIGINT NOT NULL REFERENCES mf_copybooks(id) ON DELETE CASCADE,
  field_path  VARCHAR(300) NOT NULL,
  function    VARCHAR(60)  NOT NULL,
  param1      VARCHAR(300),
  param2      VARCHAR(300),
  CONSTRAINT uq_mf_mask UNIQUE (copybook_id, field_path)
);

CREATE TABLE mf_jobs (
  id                   BIGSERIAL PRIMARY KEY,
  name                 VARCHAR(160) NOT NULL,
  status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  source_connection_id BIGINT REFERENCES mf_connections(id),
  target_connection_id BIGINT REFERENCES mf_connections(id),
  masking_seed         VARCHAR(120),
  message              TEXT,
  files_total          INTEGER NOT NULL DEFAULT 0,
  files_done           INTEGER NOT NULL DEFAULT 0,
  records_processed    BIGINT  NOT NULL DEFAULT 0,
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at           TIMESTAMP,
  finished_at          TIMESTAMP
);

CREATE TABLE mf_job_files (
  id                   BIGSERIAL PRIMARY KEY,
  job_id               BIGINT NOT NULL REFERENCES mf_jobs(id) ON DELETE CASCADE,
  source_name          VARCHAR(400) NOT NULL,           -- dataset / file name on the source LPAR
  copybook_id          BIGINT REFERENCES mf_copybooks(id),
  recfm                VARCHAR(8)  NOT NULL DEFAULT 'FB', -- FB | VB
  lrecl                INTEGER,
  code_page            VARCHAR(40),
  target_connection_id BIGINT REFERENCES mf_connections(id),   -- null = use the job target LPAR
  target_name          VARCHAR(400),                    -- null = same name as source
  status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  record_count         BIGINT NOT NULL DEFAULT 0,
  message              TEXT,
  ordinal              INTEGER NOT NULL DEFAULT 0
);
