-- Target environments: registered hosts (Delphix "environments") that mount
-- NFS-exported ZFS clones and run the VDB database engine locally.

CREATE TABLE target_environments (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(160) NOT NULL UNIQUE,
  host        VARCHAR(255) NOT NULL,
  ssh_user    VARCHAR(120) NOT NULL DEFAULT 'root',
  ssh_port    INTEGER NOT NULL DEFAULT 22,
  mount_base  VARCHAR(500) NOT NULL DEFAULT '/mnt/forgetdm',
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE virtual_databases ADD COLUMN environment_id BIGINT;
