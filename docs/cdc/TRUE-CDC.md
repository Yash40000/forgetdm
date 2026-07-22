# True (log-based) Change Data Capture

Addresses the RFP gap: *"only altered blocks synced daily"* (§3.1.1 Incremental Capture / CDC).

## What changed

ForgeTDM's TimeFlow engine already did **snapshot** incremental sync — re-read a table in PK
order, chunk it, content-address the chunks so only changed blocks are stored. That still reads
the whole table each cycle. This feature adds **true CDC**: it reads the database transaction log
so only rows actually changed since the last checkpoint are captured — no rescan.

Engine support today: **PostgreSQL** (logical replication via the built-in `test_decoding`
output plugin, LSN checkpoint) and **Oracle** (LogMiner over the redo log, SCN checkpoint) —
both live-proven end-to-end (see the evidence docs). The design is provider-based (`CdcProvider`),
so MySQL binlog and others can be added without touching the service.

| Engine | Mechanism | Checkpoint | Provider |
|---|---|---|---|
| PostgreSQL | logical replication (`test_decoding`) | LSN | `PostgresCdcProvider` |
| Oracle | LogMiner (redo log) | SCN | `OracleCdcProvider` |

## Components

| Piece | File |
|---|---|
| Migration (capture + change buffer) | `src/main/resources/db/migration/V70__cdc_capture.sql` |
| Entities / repos | `src/main/java/io/forgetdm/cdc/Cdc*Entity.java`, `Cdc*Repository.java` |
| Provider SPI | `src/main/java/io/forgetdm/cdc/CdcProvider.java` |
| Postgres provider (slot + streaming + `test_decoding` parser + LSN checkpoint) | `PostgresCdcProvider.java` |
| Orchestration (ownership + audit) | `CdcService.java` |
| Incremental apply (netted UPSERT/DELETE to a target) | `CdcIncrementalApplier.java` |
| REST API | `CdcController.java` (`/api/cdc/...`) |

Captures are governed like the data source they read (owner/group/visibility via
`OwnershipGuard`) and every action is audited: `CDC_ENABLED`, `CDC_DISABLED`, `CDC_POLLED`,
`CDC_POLL_FAILED`, `CDC_APPLIED`.

## API

```
GET  /api/cdc/datasources/{id}/preflight   -> wal_level, replication privilege, slot capacity
POST /api/cdc/datasources/{id}/enable      -> create slot, start capturing  { schema?, tables? }
GET  /api/cdc/datasources/{id}/status      -> active, slot, confirmedLsn, rowsCaptured, buffered
POST /api/cdc/datasources/{id}/poll        -> read WAL now, buffer changes, advance LSN
GET  /api/cdc/datasources/{id}/changes     -> recent decoded changes (the change feed)  ?limit=
POST /api/cdc/datasources/{id}/apply       -> incremental refresh to target  { targetDataSourceId, purge? }
POST /api/cdc/datasources/{id}/disable     -> drop slot
```

## Server prerequisites (Postgres source)

1. `wal_level = logical` (postgresql.conf or `ALTER SYSTEM SET wal_level = logical;` + restart).
2. The connection role has `REPLICATION` (`ALTER ROLE <user> WITH REPLICATION;`).
3. `pg_hba.conf` permits replication connections for that user/host.
4. For UPDATE/DELETE to include full column images, set `REPLICA IDENTITY FULL` on tables that
   need old values; with the default identity, DELETE/UPDATE carry the primary key (enough to
   net and apply upserts/deletes).

`/preflight` reports each of these with the exact remediation command.

## How to test live

1. `POST /api/cdc/datasources/2/preflight` — confirm `ok:true` (or follow the remediation).
2. `POST /api/cdc/datasources/2/enable` `{ "schema": "yash" }` — creates slot `forgetdm_cdc_2`.
3. In the source, run some DML on a table with a primary key, e.g.
   `INSERT`, `UPDATE`, `DELETE` a couple of rows in `yash.<table>`.
4. `POST /api/cdc/datasources/2/poll` — response shows `captured` = number of changed rows and a
   new `confirmedLsn`. Only the changed rows appear — no full-table read.
5. `GET /api/cdc/datasources/2/changes?limit=50` — inspect the decoded feed (op, pk, values).
6. `POST /api/cdc/datasources/2/apply` `{ "targetDataSourceId": <target>, "purge": true }` — the
   target receives netted UPSERT/DELETE for only the changed rows.
7. `POST /api/cdc/datasources/2/poll` again with no new DML — `captured:0`, LSN unchanged:
   proves resumability (no re-capture of already-consumed WAL).
