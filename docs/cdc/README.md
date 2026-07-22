# Change Data Capture (CDC) in ForgeTDM

True, log-based Change Data Capture — reading the database transaction log so only rows that
actually changed since the last checkpoint are captured, with no table rescan. This is the
answer to the requirement *"only altered blocks synced daily"* (incremental capture / CDC).

It complements the existing TimeFlow **snapshot** incremental sync (which re-reads a table in
PK order and content-addresses the chunks): CDC removes the full read entirely.

## Supported engines

| Engine | Mechanism | Server object | Checkpoint | Provider |
|---|---|---|---|---|
| PostgreSQL | logical replication, `test_decoding` output plugin | replication slot | LSN | `PostgresCdcProvider` |
| Oracle | LogMiner over redo log | none (session) | SCN | `OracleCdcProvider` |

Both are **live-proven end-to-end** (capture → resumable checkpoint → incremental apply). See
[Postgres evidence](TRUE-CDC-EVIDENCE.md) and [Oracle evidence](TRUE-CDC-ORACLE-EVIDENCE.md).

The design is provider-based (`CdcProvider` SPI), so MySQL binlog / SQL Server CDC / DB2 can be
added without touching the service, controller, or storage.

## How it works

1. **Preflight** (`GET …/preflight`) checks the engine is configured for log-based CDC and reports
   each missing prerequisite with the exact remediation command.
2. **Enable** (`POST …/enable`) creates the capture: Postgres creates a replication slot; Oracle
   records the current SCN. The owning user/group is stamped for tenancy.
3. **Poll** (`POST …/poll`) reads changes from the log since the last checkpoint, decodes each into a
   row change (op + primary key + column values), buffers them, and advances the checkpoint so the
   server can reclaim log space. Only changed rows are read.
4. **Changes feed** (`GET …/changes`) exposes the buffered change stream.
5. **Incremental apply** (`POST …/apply`) nets the buffer per primary key (last-write-wins; a
   trailing delete cancels earlier inserts/updates) and writes the minimum set of UPSERT/DELETE
   statements to a target — an incremental refresh, not a reload.
6. **Disable** (`POST …/disable`) drops the Postgres slot / marks the Oracle capture inactive.

A background sweep polls every active capture at `FORGETDM_CDC_INTERVAL_MS` (30 seconds by default).
Set `FORGETDM_CDC_CONTINUOUS_ENABLED=false` only for a manual-poll deployment. Status includes the
current source-log position and checkpoint lag in engine-specific units: WAL bytes for PostgreSQL
and SCN delta for Oracle. A transient poll failure is retained in `lastError` and retried on the next
sweep; it does not silently disable the capture.

Every material action is audited (`CDC_ENABLED`, `CDC_POLLED`, `CDC_APPLIED`, `CDC_DISABLED`,
`CDC_POLL_FAILED`) and governed by the virtualization permission family + object-level ownership.

## Components

| Piece | Path |
|---|---|
| Migration | `src/main/resources/db/migration/V71__cdc_capture.sql` |
| Entities / repos | `io/forgetdm/cdc/Cdc{Capture,Change}Entity.java`, `Cdc*Repository.java` |
| Provider SPI | `io/forgetdm/cdc/CdcProvider.java` |
| Postgres provider | `io/forgetdm/cdc/PostgresCdcProvider.java` |
| Oracle provider | `io/forgetdm/cdc/OracleCdcProvider.java` |
| Orchestration | `io/forgetdm/cdc/CdcService.java` |
| Incremental apply | `io/forgetdm/cdc/CdcIncrementalApplier.java` |
| Continuous poller | `io/forgetdm/cdc/CdcPollScheduler.java` |
| REST API | `io/forgetdm/cdc/CdcController.java` (`/api/cdc/...`) |

## API

```
GET  /api/cdc/datasources/{id}/preflight
POST /api/cdc/datasources/{id}/enable      { schema?, tables? }
GET  /api/cdc/datasources/{id}/status
POST /api/cdc/datasources/{id}/poll
GET  /api/cdc/datasources/{id}/changes     ?limit=
POST /api/cdc/datasources/{id}/apply       { targetDataSourceId, purge? }
POST /api/cdc/datasources/{id}/disable
```

## Engine prerequisites

**PostgreSQL** — `wal_level = logical` (needs restart); connection role has `REPLICATION`;
`pg_hba.conf` permits replication connections. Preflight reports each.

**Oracle** — LogMiner grants (`SELECT ANY DICTIONARY`, `EXECUTE_CATALOG_ROLE`,
`SELECT ANY TRANSACTION`; on 12c+ also `LOGMINING`); supplemental logging
(`ADD SUPPLEMENTAL LOG DATA` + `(PRIMARY KEY) COLUMNS`, no restart). ARCHIVELOG is a
*recommendation* — without it, only the current online redo log is mineable (fine for recent
changes; enable ARCHIVELOG for durable history).

## Known limitations / roadmap

Where the capability sits today versus a mature commercial data-virtualization baseline, scoped to
the two supported engines:

- **Continuous polling is enabled by default.** Manual poll remains available and a controlled
  environment can opt out with `FORGETDM_CDC_CONTINUOUS_ENABLED=false`. Lag is surfaced but alert
  thresholds and external notification routing remain deployment policy.
- **No point-in-time provisioning yet.** Snapshots (TimeFlow) and the change feed exist but are not
  yet combined to provision/rewind a VDB to an arbitrary SCN/timestamp between snapshots. *This is
  the flagship next step* — replay buffered changes onto a snapshot during provision/rewind.
- **Apply is per-PK netted, not transaction-atomic.** `xid`/SCN is captured but apply does not yet
  replay whole transactions atomically.
- **DDL is not tracked.** `test_decoding` / LogMiner skip DDL; a schema change mid-stream would
  desync apply. *Next:* capture and reconcile schema changes across the timeline.
- **Cross-engine apply identifier case.** Oracle reports identifiers upper-case; applying an Oracle
  stream to a lower-case PostgreSQL target needs case-folding in `CdcIncrementalApplier`
  (Oracle→Oracle and Postgres→Postgres work as-is).
- **Single-threaded, budgeted poll.** Fine for moderate change rates; high-TPS sources want parallel
  apply and tuned batch sizing.
- **Retention / gap handling.** Basic checkpointing and transient-error retries are present; there is
  no archive-log retention policy or automatic recovery from a permanently lost WAL/redo gap yet.
- **Breadth.** Two engines, lightly version-tested (PostgreSQL 17, Oracle XE 11.2). Broader
  engine/version certification is future work.

Already at parity: real log-based capture on both engines, resumable checkpoints, a change-feed
API, incremental apply, tamper-evident audit, RBAC + object ownership, and VDB lifecycle
(snapshot / rewind / refresh / bookmark).
