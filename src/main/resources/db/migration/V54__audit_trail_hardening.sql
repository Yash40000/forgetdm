-- Audit trail hardening: structured, categorized, tamper-evident events.
-- Turns the flat (actor, action, detail) log into CloudTrail-style evidence:
-- who / where (ip) / what (action + category) / which resource / outcome / severity,
-- plus an append-only hash chain (prev_hash -> hash) so tampering is detectable.

ALTER TABLE audit_events ADD COLUMN seq           BIGINT;
ALTER TABLE audit_events ADD COLUMN category      VARCHAR(60);
ALTER TABLE audit_events ADD COLUMN resource_type VARCHAR(80);
ALTER TABLE audit_events ADD COLUMN resource_id   VARCHAR(200);
ALTER TABLE audit_events ADD COLUMN resource_name VARCHAR(400);
ALTER TABLE audit_events ADD COLUMN outcome       VARCHAR(20);
ALTER TABLE audit_events ADD COLUMN severity      VARCHAR(20);
ALTER TABLE audit_events ADD COLUMN ip_address    VARCHAR(64);
ALTER TABLE audit_events ADD COLUMN user_agent    VARCHAR(400);
ALTER TABLE audit_events ADD COLUMN metadata      TEXT;
ALTER TABLE audit_events ADD COLUMN prev_hash     VARCHAR(88);
ALTER TABLE audit_events ADD COLUMN hash          VARCHAR(88);

-- Seed existing rows so the chain has a stable base; legacy rows keep NULL hashes
-- (they predate integrity protection and are reported as "legacy" by the verifier).
UPDATE audit_events SET seq = id           WHERE seq IS NULL;
UPDATE audit_events SET outcome = 'SUCCESS' WHERE outcome IS NULL;
UPDATE audit_events SET severity = 'INFO'   WHERE severity IS NULL;
UPDATE audit_events SET category = 'GENERAL' WHERE category IS NULL;

CREATE INDEX IF NOT EXISTS ix_audit_created_at ON audit_events (created_at);
CREATE INDEX IF NOT EXISTS ix_audit_actor      ON audit_events (actor);
CREATE INDEX IF NOT EXISTS ix_audit_action     ON audit_events (action);
CREATE INDEX IF NOT EXISTS ix_audit_category   ON audit_events (category);
CREATE INDEX IF NOT EXISTS ix_audit_outcome    ON audit_events (outcome);
CREATE INDEX IF NOT EXISTS ix_audit_seq        ON audit_events (seq);
