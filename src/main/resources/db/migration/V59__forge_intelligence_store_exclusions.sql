ALTER TABLE forge_ai_documents ADD COLUMN excluded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE forge_ai_documents ADD COLUMN excluded_by VARCHAR(120);
ALTER TABLE forge_ai_documents ADD COLUMN excluded_at TIMESTAMP;

CREATE INDEX idx_forge_ai_documents_excluded ON forge_ai_documents(excluded, active);
