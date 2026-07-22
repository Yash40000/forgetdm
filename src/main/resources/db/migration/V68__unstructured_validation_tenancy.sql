-- V68 - Tenant ownership for unstructured masking and validation evidence (RBAC-002)
--
-- New rows are stamped by the application. Existing rows remain SHARED so upgrading
-- does not hide historical profiles, encrypted job evidence, or validation reports.

ALTER TABLE unstructured_masking_profiles ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE unstructured_masking_profiles ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE unstructured_masking_profiles ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE unstructured_masking_profiles ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';

UPDATE unstructured_masking_profiles
   SET owner_username = created_by
 WHERE owner_username IS NULL;

UPDATE unstructured_masking_profiles p
   SET owner_user_id = u.id
  FROM forge_users u
 WHERE p.owner_user_id IS NULL
   AND LOWER(u.username) = LOWER(p.created_by);

UPDATE unstructured_masking_profiles SET visibility = 'SHARED';

CREATE INDEX IF NOT EXISTS idx_unstructured_profiles_tenancy
    ON unstructured_masking_profiles (visibility, owner_group_id, owner_user_id);

ALTER TABLE unstructured_masking_jobs ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE unstructured_masking_jobs ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE unstructured_masking_jobs ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE unstructured_masking_jobs ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';

UPDATE unstructured_masking_jobs
   SET owner_username = created_by
 WHERE owner_username IS NULL;

UPDATE unstructured_masking_jobs j
   SET owner_user_id = u.id
  FROM forge_users u
 WHERE j.owner_user_id IS NULL
   AND LOWER(u.username) = LOWER(j.created_by);

UPDATE unstructured_masking_jobs SET visibility = 'SHARED';

CREATE INDEX IF NOT EXISTS idx_unstructured_jobs_tenancy_v68
    ON unstructured_masking_jobs (visibility, owner_group_id, owner_user_id);

ALTER TABLE validation_reports ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE validation_reports ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE validation_reports ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE validation_reports ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';

UPDATE validation_reports SET visibility = 'SHARED';

CREATE INDEX IF NOT EXISTS idx_validation_reports_tenancy
    ON validation_reports (visibility, owner_group_id, owner_user_id);
