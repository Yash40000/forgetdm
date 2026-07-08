-- On a constraint failure during load (e.g. a masked value colliding with a unique index), the job stores a
-- diagnostic here: the failed record (original vs masked) and the conflicting record, so the UI can show both
-- without a DBA having to reproduce the error.
ALTER TABLE provision_jobs ADD COLUMN conflict_json TEXT;
