-- V62 — Audit sequence integrity (DEF-0008 / DEF-0009, found by AUD-001-08)
--
-- The audit sequence was allocated by an in-JVM counter (++lastSeq) guarded only by `synchronized`,
-- with no uniqueness in the database. Two instances (or a double start) allocated the same number,
-- producing two rows with identical seq AND identical prev_hash — a forked hash chain. Live evidence:
-- seq 702 and 703 each existed twice, and /api/audit/verify reported valid:false, brokenAtSeq:702,
-- with only 11 of 3,005 events verified.
--
-- Rolled-back audit writes made it worse: the counter advanced for rows that never committed, leaving
-- sequence gaps (maxSeq 3046 vs 3,010 rows) and a prev_hash pointing at a non-existent row.
--
-- This migration:
--   1. repairs the existing duplicates WITHOUT deleting any audit row (append-only is the point);
--   2. creates a Postgres sequence so allocation is atomic across instances;
--   3. adds UNIQUE(seq) so a future fork fails loudly at write time instead of corrupting silently.

-- ── 1. Repair duplicate sequence numbers ─────────────────────────────────────
-- Keep the earliest row (lowest id) for each seq; move later duplicates to fresh numbers above the
-- current maximum. No row is removed. The affected rows remain outside the verified chain — their
-- link is already broken and rewriting hashes to "fix" it would defeat tamper-evidence. Verification
-- (V62 + DEF-0009 code change) now reports these as link breaks and continues past them.
WITH ranked AS (
    SELECT id,
           seq,
           ROW_NUMBER() OVER (PARTITION BY seq ORDER BY id) AS rn
      FROM audit_events
     WHERE seq IS NOT NULL
),
dupes AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS offset_n
      FROM ranked
     WHERE rn > 1
)
UPDATE audit_events a
   SET seq = (SELECT COALESCE(MAX(seq), 0) FROM audit_events) + d.offset_n
  FROM dupes d
 WHERE a.id = d.id;

-- Any row that somehow has no seq gets one so the UNIQUE index below is meaningful.
WITH missing AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS offset_n
      FROM audit_events
     WHERE seq IS NULL
)
UPDATE audit_events a
   SET seq = (SELECT COALESCE(MAX(seq), 0) FROM audit_events) + m.offset_n
  FROM missing m
 WHERE a.id = m.id;

-- ── 2. Atomic allocation ─────────────────────────────────────────────────────
-- Start above the highest existing seq so new events never collide with history.
DO $$
DECLARE next_start BIGINT;
BEGIN
    SELECT COALESCE(MAX(seq), 0) + 1 INTO next_start FROM audit_events;
    EXECUTE format('CREATE SEQUENCE IF NOT EXISTS audit_event_seq START WITH %s', next_start);
    -- If the sequence already existed (re-run / partial upgrade), fast-forward it past history.
    EXECUTE format('SELECT setval(''audit_event_seq'', %s, false)', next_start);
END $$;

-- ── 3. Enforce uniqueness ────────────────────────────────────────────────────
CREATE UNIQUE INDEX IF NOT EXISTS uq_audit_events_seq ON audit_events (seq);
