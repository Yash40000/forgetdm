# True CDC (Oracle) — live execution evidence

**Date:** 2026-07-21
**Build:** ForgeTDM 1.0.0, backend on :8088
**Source:** data source id 11 `BE Lab - Card Servicing Oracle` → `jdbc:oracle:thin:@localhost:1521:XE`
(Oracle Database XE 11.2.0), schema `BE_CARDS`
**Operator:** admin (admin.all)
**Feature:** log-based Change Data Capture via **Oracle LogMiner** (redo log)

Extends the true-CDC capability from PostgreSQL to Oracle through the same `CdcProvider` SPI.
Changes are read from the redo log — no table rescan.

## 0. Environment prep (DBA, one-time)

LogMiner grants (Oracle 11g; `LOGMINING` is 12c+, so 11g uses the catalog role):

```sql
GRANT SELECT ANY DICTIONARY TO BE_CARDS;
GRANT EXECUTE_CATALOG_ROLE TO BE_CARDS;
GRANT SELECT ANY TRANSACTION TO BE_CARDS;
```

Supplemental logging (no restart required):

```sql
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (PRIMARY KEY) COLUMNS;
```

ARCHIVELOG was **not** enabled. LogMiner mines the current **online** redo log, which is sufficient
to capture recent changes; ARCHIVELOG is required only to reach changes that have already rotated
out of the online logs (the provider reports this as a recommendation, not a blocker).

## 1. Preflight

`GET /api/cdc/datasources/11/preflight` progressed exactly as the DBA steps were applied:

1. Before grants → `privileged:false`, message: grant LogMiner access to `BE_CARDS` (exact SQL).
2. After grants, before supplemental logging → `privileged:true`, flagged NOARCHIVELOG + supplemental off.
3. After supplemental logging →

```json
{ "mechanism":"Oracle LogMiner (redo log)", "logLevel":"NOARCHIVELOG",
  "privileged":true, "ok":true,
  "messages":["Recommended: … NOARCHIVELOG … only changes still in the current online redo log …"] }
```

## 2. Enable — SCN checkpoint recorded (no server-side slot)

`POST /api/cdc/datasources/11/enable  { "schema":"BE_CARDS" }`

```json
{ "active":true, "status":"ACTIVE", "slotName":"forgetdm_cdc_11",
  "restartLsn":"4377303", "confirmedLsn":"4377303" }
```

(For Oracle the checkpoint is the SCN; there is no replication slot to create/drop.)

## 3. Source DML (committed)

```sql
CREATE TABLE BE_CARDS.CDC_DEMO (id NUMBER PRIMARY KEY, name VARCHAR2(50), amount NUMBER);
INSERT INTO BE_CARDS.CDC_DEMO VALUES (1,'Alice',100);
INSERT INTO BE_CARDS.CDC_DEMO VALUES (2,'Bob',200);
UPDATE BE_CARDS.CDC_DEMO SET amount=250, name='Bob Jones' WHERE id=2;
DELETE FROM BE_CARDS.CDC_DEMO WHERE id=1;
COMMIT;
```

## 4. Poll — 4 changes decoded from SQL_REDO

`POST /api/cdc/datasources/11/poll` → `{ "captured":4, "decoded":4, "confirmedLsn":"4377374", "reachedEnd":true }`

`GET …/changes` (parsed from `V$LOGMNR_CONTENTS.SQL_REDO`, PK extracted from `ALL_CONS_COLUMNS`):

| id | op | table | pk | values | scn |
|----|----|-------|----|--------|-----|
| 8 | D | BE_CARDS.CDC_DEMO | {ID:1} | {ID:1,NAME:Alice,AMOUNT:100} | 4377360 |
| 7 | U | BE_CARDS.CDC_DEMO | {ID:2} | {NAME:"Bob Jones",AMOUNT:250,ID:2} | 4377360 |
| 6 | I | BE_CARDS.CDC_DEMO | {ID:2} | {ID:2,NAME:Bob,AMOUNT:200} | 4377360 |
| 5 | I | BE_CARDS.CDC_DEMO | {ID:1} | {ID:1,NAME:Alice,AMOUNT:100} | 4377360 |

SCN advanced `4377303 → 4377374`. No table read occurred.

## 5. Resumability

`POST …/poll` again with no new DML → `{ "captured":0, "decoded":0, "confirmedLsn":"4377385" }`.
The 4 changes were not re-captured. (Oracle's SCN advances continuously from internal activity, so
`confirmedLsn` moves forward on its own; the checkpoint tracks it correctly.)

## Notes

- **Cross-engine apply caveat:** Oracle reports identifiers upper-case (`ID`, `NAME`). Applying an
  Oracle change stream to a **PostgreSQL** target whose columns are lower-case would need identifier
  case-folding in `CdcIncrementalApplier` (it quotes identifiers, which Postgres treats
  case-sensitively). Oracle→Oracle apply is the natural pairing; that mapping is a small follow-up.
- **Update images:** with `(PRIMARY KEY) COLUMNS` supplemental logging, an UPDATE's `SQL_REDO` carries
  the changed columns plus the PK. Use `(ALL) COLUMNS` if full row images are required on the target.
- Cleanup: `POST /api/cdc/datasources/11/disable` (no-op server-side for Oracle) marks the capture
  inactive.
