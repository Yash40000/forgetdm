# DEF-0017 — Data-source create/update accepts invalid input and persists it

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | **CLOSED** — fixed and verified live 2026-07-18 |
| Found by story | DSRC-001 — case DSRC-001-02 |
| Component | `io.forgetdm.datasource.DataSourceService` (`create`, `update`) |
| Found on | Live stack 2026-07-18 |

## Summary

`POST /api/datasources` performs **no input validation**. Four of six deliberately invalid payloads
were accepted with `200` and persisted:

| Input | Expected | Actual |
|---|---|---|
| Blank name (`""`) | reject | **200 — row created with an empty name** |
| Malformed JDBC URL (`not-a-jdbc-url`) | reject | **200 — row created** |
| Unsupported role (`WIZARD`) | reject | **200 — row created with role `WIZARD`** |
| Unsupported engine (`NOTADB`) | reject | **200 — row created with kind `NOTADB`** |
| Duplicate name | reject | rejected, but as a raw 500 (see [DEF-0018](DEF-0018-raw-db-exception-leaked-to-clients.md)) |
| Missing JDBC URL | clean 400 | **500** with the SQL statement and failing row |

Connection count went **12 → 16**, so DSRC-001-02's requirement that "no partial row is persisted" is
violated. The junk rows created were ids 20 (`name=""`), 22 (bad URL), 23 (`role=WIZARD`),
24 (`kind=NOTADB`).

## Root cause

Nothing validates the entity before `repo.save()`. The only rejections come from database
constraints (`UNIQUE(name)`, `NOT NULL(jdbc_url)`), which surface as 500s rather than 4xx.

Unsupported engines are silently tolerated because `SqlDialect.of()` falls through for an unknown
kind and resolves from the URL, ultimately returning `GENERIC`:

```java
default: break; // GENERIC or unknown -> try URL
```

So `kind=NOTADB` with a malformed URL yields a stored connection that can never connect but looks
valid in the UI. Compare masking policies, which *do* validate (`PolicyNameRules`, 8–120 chars).

## Impact

Unusable connections enter the catalogue and only fail later at job-launch time, far from the cause.
An empty-named connection is effectively unclickable in the UI. This is a data-quality and
diagnosability defect rather than a security one.

## Fix

Added a `validate(ds, id)` step called by both `create` and `update`:

- name required, trimmed, 3–120 chars, and unique (case-insensitive) → `409` instead of a DB 500;
  the `id` parameter lets a rename keep its own name.
- `jdbcUrl` required and must start with `jdbc:`.
- `role` must be `SOURCE` / `TARGET` / `BOTH`.
- `kind` must be one of the identifiers `SqlDialect.of` actually understands (including aliases:
  POSTGRESQL, MARIADB, DB2UDB, MSSQL, VANTAGE …).

Values are normalised (trimmed/upper-cased) on the way in, so the stored row matches what the dialect
resolver expects.

**Cleanup required:** rows 20, 22, 23, 24 were created by this test and should be deleted. *(Done.)*

## Verification (live, 2026-07-18, after rebuild)

Same six payloads replayed. Every one rejected with a field-specific message, and the connection
count was unchanged (**11 → 11**), satisfying "no partial row is persisted".

| Input | Before | After |
|---|---|---|
| Blank name | 200, row created | **400** — "Connection name must be between 3 and 120 characters" |
| Malformed JDBC URL | 200, row created | **400** — "JDBC URL is required and must start with 'jdbc:'" |
| Role `WIZARD` | 200, row created | **400** — "Role must be one of SOURCE, TARGET or BOTH" |
| Engine `NOTADB` | 200, row created | **400** — "Unsupported engine 'NOTADB'. Supported: POSTGRES, H2, MYSQL, DB2, ORACLE, SQLSERVER, TERADATA, GENERIC" |
| Duplicate name | 500 (raw SQL) | **409** — "A connection named '…' already exists" |
| Missing URL | 500 (raw SQL + row) | **400** — clean message |
