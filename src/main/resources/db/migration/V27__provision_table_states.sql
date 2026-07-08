-- Per-table provisioning state for live progress on the DataScope provision / Table Map view
-- (PENDING / RUNNING / DONE / FAILED + row counts), mirroring synthetic-generation progress.
ALTER TABLE provision_jobs ADD COLUMN table_states_json TEXT;
