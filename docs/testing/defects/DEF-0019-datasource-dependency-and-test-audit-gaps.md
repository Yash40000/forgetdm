# DEF-0019 — Referenced-connection delete names no dependencies; connection tests are unaudited

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | **CLOSED** — fixed and verified live 2026-07-18 |
| Found by story | DSRC-001 — cases DSRC-001-06 and DSRC-001-08 |
| Component | `io.forgetdm.datasource.DataSourceService` (`delete`, `testConnection`) |
| Found on | Live stack 2026-07-18 |

Two related gaps in the data-source lifecycle, both found live.

## 1. Delete blocked without naming the dependency (DSRC-001-06)

Deleting a referenced connection *is* correctly prevented and the data survives — but only by the
database foreign key, which surfaces as an opaque 500:

```
500 {"error":"DataIntegrityViolationException: could not execute statement
     [ERROR: update or delete on table \"data_sources\" violates foreign key …"}
```

DSRC-001-06 requires the block to come "**with dependency names** or a documented safe cascade".
Live, `sourceDB` (id 2) was referenced by **3 masking policies and 3 DataScope blueprints**, yet the
response identified none of them, leaving the operator no way to discover what to unpick.

Deleting an *unused* connection correctly returned `200`.

## 2. Connection tests are not audited (DSRC-001-08)

`POST /api/datasources/{id}/test` produced **no audit event of any kind** — a query for test-related
actions returned an empty set, while the trail held 66 `DATA_SOURCE` events (`DATASOURCE_CREATED` 8,
`DATASOURCE_UPDATED` 6, `DATASOURCE_DELETED` 6). DSRC-001-08 explicitly lists "create/update/**test**/
delete events".

Testing a connection uses stored credentials against a remote system. Its absence means credential
use — and repeated failed probes, a useful attack signal — is invisible.

Create/update/delete events did record actor and outcome correctly, but carried no
`resourceType`/`resourceId`/`resourceName` and no role/engine (the DEF-0010 pattern).

## Impact

Operators cannot resolve a blocked delete without querying the database directly, and a class of
credential-use activity goes unrecorded. Neither loses or exposes data — the FK holds and the delete
is refused.

## Fix

**Dependency naming.** `delete()` now pre-checks and fails with a `409` that lists what is blocking:

> `Cannot delete 'sourceDB' because it is still referenced by: policy 'policy-for-run', blueprint
> 'plicy-for-run', … Remove or repoint these first.`

Implemented as direct SQL over `masking_policies`, `dataset_definitions` (source *and* target) and
active `reservations`, capped at 10 names. It queries tables rather than calling the owning services
because `DataSetService` already depends on `DataSourceService` — the reverse would be a circular
bean dependency. A missing table is swallowed so it can never block a delete the FK would permit.

**Test auditing.** `testConnection()` now records `DATASOURCE_TESTED` with actor, resource identity,
engine, redacted URL and `SUCCESS`/`FAILURE` — failures included, since a run of them is the
interesting signal.

Create/update/delete were also moved to the structured `record(...)` form so they carry resource
identity plus role and engine.

## Verification (live, 2026-07-18, after rebuild)

**Dependency naming.** `DELETE /api/datasources/2` now returns **409** (was 500), with no SQL leak,
naming every blocker:

> `Cannot delete 'sourceDB' because it is still referenced by: policy 'policy-for-run',
> policy 'POLICY-2NOW', policy 'NEW-POLICY', blueprint 'devdas', blueprint 'dev',
> blueprint 'plicy-for-run'`

Exactly the 3 policies + 3 blueprints identified by querying the tables directly during the original
finding. The data source survived the attempt.

**Test auditing.** `DATASOURCE_TESTED` events went **0 → 1** on a single connection test, with full
structured identity:

```
actor=admin  action=DATASOURCE_TESTED  outcome=SUCCESS
resourceType=datasource  resourceId=2  resourceName=sourceDB
detail=sourceDB (engine=POSTGRES url=jdbc:postgresql://localhost:5433/sourcedb)
```

Resource identity is populated (the DEF-0010 pattern applied here) and the URL passes through
`safeJdbc` redaction.
