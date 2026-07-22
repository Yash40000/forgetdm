# Db2 CDC — design and implementation plan

**Status:** design only. No live Db2 instance is available to build/verify against, so this is a
plan, not shipped code. It plugs into the existing `CdcProvider` SPI exactly like the PostgreSQL
and Oracle providers.

## Why Db2 is different

Db2 has no built-in, JDBC-reachable log-decoding interface equivalent to PostgreSQL logical
replication (`test_decoding`) or Oracle LogMiner (`V$LOGMNR_CONTENTS`). There are two real options,
and only one fits ForgeTDM's pure-JDBC connector.

### Option A — `db2ReadLog` raw log API (rejected for now)

`db2ReadLog()` reads the recovery log directly and returns raw log records. Problems:

- It is a **native C API** (also exposed through the CLI/`.NET`), with **no SQL or JDBC entry
  point**. ForgeTDM's connector layer is pure JDBC, so calling it would require JNI/native code.
- The returned records are **binary log structures** (log record headers, LSNs, compressed row
  images) that must be decoded against Db2's internal format — fragile and effectively impossible
  to get right without iterating against a live instance.

This is the same reason IBM's own and third-party tools (e.g. Debezium's Db2 connector) do **not**
use `db2ReadLog` for general CDC. Rejected unless/until native integration is justified.

### Option B — ASN SQL-replication capture tables (recommended)

Db2's **SQL Replication** ships a capture agent (`asncap`) that reads the recovery log and writes
committed row changes into **change-data (CD) tables**, tracked by control tables in the `ASN`
schema (`IBMSNAP_REGISTER`, `IBMSNAP_CAPMON`, …). Those CD tables are ordinary tables — readable
over **JDBC** — so they fit ForgeTDM's poll model directly. This is the approach Debezium uses.

## Prerequisites (DBA, one-time)

1. **Archive logging** on the source database: `LOGARCHMETH1` set to `LOGRETAIN`/`DISK:…` (not
   circular). Requires a backup after switching.
2. **Install the replication control tables**: `asnclp` → `CREATE CONTROL TABLES FOR CAPTURE SERVER`.
3. **Register each captured table**: `asnclp` → `CREATE REGISTRATION`, which creates its CD table
   (`ASN.CDxxxx`) and starts logging the needed columns (Db2 sets `DATA CAPTURE CHANGES` on the
   source table).
4. **Run the capture agent**: `asncap capture_server=<db>` — it tails the log into the CD tables.
5. **Grant** the ForgeTDM source user `SELECT` on the `ASN` control + CD tables.

Preflight will check each of these and report the exact remediation, mirroring the Postgres/Oracle
providers.

## Mapping onto the `CdcProvider` SPI

| SPI concept | Db2 (ASN) realisation |
|---|---|
| `mechanism()` | "Db2 SQL replication (ASN capture)" |
| `pluginName()` | "asn" |
| checkpoint (`confirmedLsn`) | `IBMSNAP_COMMITSEQ` (log sequence, hex) — highest consumed |
| `preflight()` | verify `LOGARCHMETH1` ≠ circular; `ASN` control tables exist; capture is running (`IBMSNAP_CAPMON`); registrations exist; `SELECT` grants |
| `createSlot()` | no server object — record the current max `IBMSNAP_COMMITSEQ` as the start checkpoint (there is nothing to drop) |
| `dropSlot()` | no-op (deactivating capture is a DBA action, not ours) |
| `poll()` | for each registered CD table, `SELECT … WHERE IBMSNAP_COMMITSEQ > :last ORDER BY IBMSNAP_COMMITSEQ, IBMSNAP_INTENTSEQ`; decode `IBMSNAP_OPERATION` (`I`/`U`/`D`); map the non-`IBMSNAP_*` columns to values; advance checkpoint to the max `IBMSNAP_COMMITSEQ` read |

### Decoding a CD-table row

- `IBMSNAP_OPERATION` → `I` (insert), `U` (update), `D` (delete). Db2 emits a delete+insert pair for
  updates unless `CHANGING_COLS` before-image is configured; the provider coalesces the U pair by
  `(IBMSNAP_COMMITSEQ, IBMSNAP_INTENTSEQ)`.
- Business columns are every column that isn't `IBMSNAP_*`. Primary key comes from the source
  table's PK (via `SYSCAT.KEYCOLUSE`), extracted from the row image — same `attachPk` pattern as the
  other providers.
- `IBMSNAP_COMMITSEQ` is the durable, resumable checkpoint (monotonic per the log).

## Poll sketch (per registered CD table)

```sql
SELECT *                              -- IBMSNAP_* control cols + business cols
FROM   ASN.CD<table>
WHERE  IBMSNAP_COMMITSEQ > ?          -- last confirmed checkpoint
ORDER  BY IBMSNAP_COMMITSEQ, IBMSNAP_INTENTSEQ
FETCH FIRST ? ROWS ONLY;
```

Advance `confirmedLsn` to `MAX(IBMSNAP_COMMITSEQ)` across the batch; the rest of the pipeline
(buffer → change feed → incremental apply) is identical to Postgres/Oracle and needs no changes.

## Effort estimate

Small–medium once a Db2 instance with capture exists: preflight (~½ day), the CD-table poll +
row decode + PK lookup (~1–2 days), and live verification (enable → DML → poll → apply), reusing
everything downstream. The gating cost is the DBA setup of archive logging + `asncap`, not the
provider code.

## Cross-cutting note

Db2 identifiers are upper-case (like Oracle), so applying a Db2 change stream to a lower-case
PostgreSQL target hits the same identifier case-folding item already tracked for Oracle→Postgres.
