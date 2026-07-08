-- Connection catalog metadata: environment classification + free-form tags.
ALTER TABLE data_sources ADD COLUMN environment VARCHAR(32);
ALTER TABLE data_sources ADD COLUMN tags TEXT;
