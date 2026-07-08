-- Maker-checker approval workflow for provisioning jobs (mirrors V24 synthetic governance).
-- approval_status: NOT_REQUIRED | PENDING_APPROVAL | APPROVED | REJECTED
ALTER TABLE provision_jobs ADD COLUMN created_by VARCHAR(120);
ALTER TABLE provision_jobs ADD COLUMN approval_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED';
ALTER TABLE provision_jobs ADD COLUMN approval_requested_at TIMESTAMP;
ALTER TABLE provision_jobs ADD COLUMN approved_at TIMESTAMP;
ALTER TABLE provision_jobs ADD COLUMN approved_by VARCHAR(120);
ALTER TABLE provision_jobs ADD COLUMN approval_note TEXT;
