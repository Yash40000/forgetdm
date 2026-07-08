-- Multi-clause conditional masking (AND/OR groups), stored as JSON. Takes precedence over the
-- single cond_* columns when present.
ALTER TABLE column_overrides ADD COLUMN cond_json TEXT;
