-- Saved transformation mappings (Informatica-style mapping designer). spec_json holds sources, joins,
-- the transformation pipeline, and the target.
CREATE TABLE mapping_definitions (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL UNIQUE,
    description TEXT,
    spec_json   TEXT NOT NULL,
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
);
