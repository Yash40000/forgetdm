-- Micro-Database / Entity Capsule hardening pass (industry-standard K2View parity):
--  * fragments carry their version_no so every version stays restorable (time travel)
--  * truncation evidence (fetch cap can no longer silently clip a fragment)
--  * per-capsule payload encryption (AES-256-GCM; key derived per instance via key_salt)
--  * sync-on-demand: capsules can declare a staleness budget and refresh at access time

ALTER TABLE be_entity_instances ADD COLUMN sync_mode VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE be_entity_instances ADD COLUMN stale_after_minutes INTEGER;
ALTER TABLE be_entity_instances ADD COLUMN key_salt VARCHAR(64);
ALTER TABLE be_entity_instances ADD COLUMN retired_at TIMESTAMP;

ALTER TABLE be_entity_fragments ADD COLUMN version_no INTEGER NOT NULL DEFAULT 0;
ALTER TABLE be_entity_fragments ADD COLUMN truncated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE be_entity_fragments ADD COLUMN encrypted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE be_entity_fragments ADD COLUMN payload_iv VARCHAR(40);

CREATE INDEX idx_be_entity_fragments_instance_version ON be_entity_fragments(instance_id, version_no);
