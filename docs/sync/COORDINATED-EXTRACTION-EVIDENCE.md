# Coordinated multi-source extraction — live evidence (RFP §3.1.1)

**Date:** 2026-07-21
**Build:** ForgeTDM 1.0.0, backend on :8088
**Operator:** admin
**Feature:** Sync Sets — coordinated snapshot across heterogeneous sources ("Timeflow Synchronization")

Proves the RFP requirement: a coordinated snapshot/extraction sequence across multiple platforms
simultaneously, pinned to a single point, without locking production tables.

## Set-up

Sync set with two members on **different engines**:

| Member | Data source | Engine | Schema |
|---|---|---|---|
| 1 | sourceDB (id 2) | PostgreSQL 17 | yash |
| 2 | BE Lab - Card Servicing Oracle (id 11) | Oracle XE 11.2 | BE_CARDS |

## Coordinated run

`POST /api/sync/sets/1/run` → one run, both members pinned at the coordination instant
`targetTs = 2026-07-21T20:05:25.8Z`, then extracted in parallel.

```json
{ "run": { "status": "SUCCESS", "memberCount": 2, "succeededCount": 2,
           "targetTs": "2026-07-21T20:05:25.8Z", "windowMs": 139578 },
  "members": [
    { "dataSourceName": "sourceDB", "mechanism": "PostgreSQL logical replication (test_decoding)",
      "consistencyPoint": "7/21692BB0", "rowCount": 1003501, "status": "SUCCESS", "elapsedMs": 45765,
      "snapshotId": 3 },
    { "dataSourceName": "BE Lab - Card Servicing Oracle", "mechanism": "Oracle LogMiner (redo log)",
      "consistencyPoint": "4380708", "rowCount": 1654301, "status": "SUCCESS", "elapsedMs": 139565 }
  ] }
```

## What it demonstrates

- **Coordination:** both sources pinned to their transaction-log position at one instant —
  Postgres LSN `7/21692BB0`, Oracle SCN `4380708` — so the set is log-aligned and can be advanced
  to a common sync point via CDC.
- **Parallelism / window:** ~2.66M rows across two engines; the run window (139.6s) ≈ the slower
  member (139.6s), not the sum (185s) — the members were extracted concurrently, within a bounded
  batch window (the RFP's overnight window).
- **Lock-free:** extraction uses the existing TimeFlow snapshot over an MVCC consistent read — no
  locks placed on production tables.
- **Governed & audited:** the sync set is owner/visibility scoped; the run wrote a `SYNC_RUN` audit
  event.

## API

```
POST /api/sync/sets                       { name, description }
POST /api/sync/sets/{id}/members          { dataSourceId, schema }
POST /api/sync/sets/{id}/run              -> coordinated run (pins LSN/SCN, parallel extract)
GET  /api/sync/sets/{id}/runs
GET  /api/sync/runs/{runId}               -> run + per-member results
```

## Notes

- The run is synchronous today; for very large sets it should move to the async operations model
  (progress polling) — the per-member results already stream into `sync_run_member` as each finishes.
- Cross-source *transactional* consistency across heterogeneous banking systems is not globally
  guaranteed (no distributed 2PC); the model is per-source consistent snapshot + pinned log position,
  aligned downstream by CDC — the standard approach for heterogeneous TDM.
