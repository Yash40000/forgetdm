# True CDC — live execution evidence

**Date:** 2026-07-21
**Build:** ForgeTDM 1.0.0, backend on :8088
**Source:** data source id 2 `sourceDB` → `jdbc:postgresql://localhost:5433/sourcedb` (PostgreSQL 17.10)
**Target:** data source id 3 `targetdb` → `jdbc:postgresql://localhost:5433/targetdb`
**Operator:** admin (admin.all)
**Feature:** log-based Change Data Capture via PostgreSQL logical replication (`test_decoding`)

Proves the RFP gap is closed: *"only altered blocks synced daily"* (§3.1.1 Incremental Capture / CDC).
Changes are read from the transaction log — no table rescan.

## 1. Preflight gating (guardrail)

Before the DBA prerequisites were met, `GET /api/cdc/datasources/2/preflight` correctly reported
the source was not ready and `POST …/enable` refused with HTTP 400 rather than creating a broken slot:

- `wal_level = replica` → flagged with the exact remediation (`ALTER SYSTEM SET wal_level = logical`).
- role `yash` lacked `REPLICATION` → flagged (`ALTER ROLE yash WITH REPLICATION`).

After `wal_level=logical` (server restart) + the replication grant, preflight returned:

```json
{ "mechanism":"PostgreSQL logical replication (test_decoding)",
  "logLevel":"logical", "privileged":true, "ok":true }
```

## 2. Enable — replication slot created

`POST /api/cdc/datasources/2/enable  { "schema":"yash" }`

```json
{ "active":true, "status":"ACTIVE", "slotName":"forgetdm_cdc_2",
  "restartLsn":"7/214414F0", "confirmedLsn":"7/21441528" }
```

## 3. Source DML (2 insert, 1 update, 1 delete)

```sql
CREATE TABLE yash.cdc_demo (id int primary key, name text, amount numeric);
INSERT INTO yash.cdc_demo VALUES (1,'Alice',100),(2,'Bob',200);
UPDATE yash.cdc_demo SET amount=250, name='Bob Jones' WHERE id=2;
DELETE FROM yash.cdc_demo WHERE id=1;
```

## 4. Poll — only the 4 changed rows captured from the WAL

`POST /api/cdc/datasources/2/poll` → `{ "captured":4, "decoded":4, "confirmedLsn":"7/2146F1F8", "reachedEnd":true }`

`GET …/changes` returned exactly (newest first), with PK extracted and transaction id `xid=146218`:

| id | op | table | pk | values | lsn |
|----|----|-------|----|--------|-----|
| 4 | D | yash.cdc_demo | {id:1} | {id:1} | 7/2146EC50 |
| 3 | U | yash.cdc_demo | {id:2} | {id:2,name:"Bob Jones",amount:250} | 7/2146EBF8 |
| 2 | I | yash.cdc_demo | {id:2} | {id:2,name:"Bob",amount:200} | 7/2146EB40 |
| 1 | I | yash.cdc_demo | {id:1} | {id:1,name:"Alice",amount:100} | 7/2146EA28 |

`confirmedLsn` advanced `7/21441528 → 7/2146F1F8`. No full-table read occurred.

## 5. Resumability — no re-capture

`POST …/poll` again with no new DML → `{ "captured":0, "decoded":0, "confirmedLsn":"7/2146F1F8" }`.
LSN unchanged; the slot did not re-read WAL it had already consumed.

## 6. Incremental apply — only changed rows written to the target

Target baseline (old replica state): `(1,Alice,100)`, `(2,Bob,200)`.

`POST /api/cdc/datasources/2/apply  { "targetDataSourceId":3, "purge":true }`

```json
{ "applied":true, "upserts":1, "deletes":1, "tables":1,
  "skippedNoPk":0, "purgedFromBuffer":4, "consumedThroughId":4 }
```

The 4 buffered changes were netted per primary key (insert+delete → delete; insert+update → upsert),
so the target received **2** statements, not a reload. Target after apply:

| id | name | amount |
|----|------|--------|
| 2 | Bob Jones | 250 |

id=1 deleted, id=2 updated — the target now matches the source's net state. Buffer purged to 0.

## 7. Audit

Each action wrote an audit event: `CDC_ENABLED`, `CDC_POLLED`, `CDC_APPLIED`.

## Notes / cleanup

- The slot `forgetdm_cdc_2` remains ACTIVE and will retain WAL until polled or dropped. Call
  `POST /api/cdc/datasources/2/disable` to drop the slot and let Postgres reclaim WAL when finished.
- `pg_hba.conf` already permitted local replication connections (default Postgres 17 config).
