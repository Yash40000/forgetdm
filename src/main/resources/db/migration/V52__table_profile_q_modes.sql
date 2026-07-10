-- Optim-parity Q1/Q2 modes per table profile: GLOBAL (null) / YES / NO / DEFER.
-- DEFER = sit out the primary FK closure, activate only after all primary extraction
-- paths converge (tames circular relationships without dropping them).
-- The legacy boolean q1_override/q2_override columns stay for the classic console;
-- when q1_mode/q2_mode is set it wins.

ALTER TABLE table_profiles ADD COLUMN q1_mode VARCHAR(8);
ALTER TABLE table_profiles ADD COLUMN q2_mode VARCHAR(8);

UPDATE table_profiles SET q1_mode = CASE WHEN q1_override = TRUE THEN 'YES' WHEN q1_override = FALSE THEN 'NO' END
 WHERE q1_override IS NOT NULL;
UPDATE table_profiles SET q2_mode = CASE WHEN q2_override = TRUE THEN 'YES' WHEN q2_override = FALSE THEN 'NO' END
 WHERE q2_override IS NOT NULL;
