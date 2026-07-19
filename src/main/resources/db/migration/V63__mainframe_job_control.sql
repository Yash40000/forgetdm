ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS created_by VARCHAR(160);

UPDATE mf_jobs SET created_by = 'system' WHERE created_by IS NULL;
