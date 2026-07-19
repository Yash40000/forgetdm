# DEF-0018 — Raw database exceptions (SQL, schema, row values) returned to API clients

| Field | Value |
|---|---|
| Severity | MEDIUM (information disclosure — application-wide) |
| Status | **CLOSED** — fixed and verified live 2026-07-18 |
| Found by story | DSRC-001 — case DSRC-001-02 (affects every endpoint) |
| Component | `io.forgetdm.common.GlobalExceptionHandler` |
| Found on | Live stack 2026-07-18 |

## Summary

Any unhandled persistence exception is echoed to the caller verbatim, including the failing SQL, the
table's full column list and the offending row's values. Live response to a `POST /api/datasources`
missing its URL (abridged):

```
{"error":"DataIntegrityViolationException: could not execute statement
 [ERROR: null value in column \"jdbc_url\" of relation \"data_sources\" violates not-null constraint
  Detail: Failing row contains (25, DSRC1-NOURL-…, POSTGRES, null, null, null, SOURCE,
          2026-07-18 10:19:42, null, null, 1, admin, 1, GROUP).]
 [insert into data_sources (created_at,environment,jdbc_url,kind,name,owner_group_id,owner_user_id,
  owner_username,password,role,tags,username,visibility) values (?,?,…) returning id]; …"}
```

**This is application-wide, not endpoint-specific.** Verified independently on
`POST /api/policies` with a duplicate name — same `DataIntegrityViolationException` body with SQL.

## Root cause

The catch-all handler returns the exception message as-is:

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<Map<String, String>> any(Exception e) {
    return ResponseEntity.status(500).body(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
}
```

Spring's `DataIntegrityViolationException` message embeds the full JDBC error, so schema internals
travel straight to the browser.

## Impact

Discloses table names, complete column lists, constraint names and stored row values (the leaked row
above includes the owner/tenancy columns added by V61) to any authenticated caller — and via error
toasts, to anyone watching the screen. That is reconnaissance material for SQL-injection probing and
for mapping the data model. Constraint violations are also mis-classified as `500` when they are
client errors, so legitimate conflicts look like server faults.

## Fix

`GlobalExceptionHandler` rewritten so no raw exception text reaches a client:

- New `@ExceptionHandler(DataIntegrityViolationException.class)` → **409 Conflict** with a stable
  message: *"That value conflicts with an existing record, or a required field was missing."*
- Catch-all → **500** with *"Unexpected server error. Quote reference `<id>` when reporting this."*
- Full detail (message + stack trace) is written to the server log against a short reference id, so
  diagnosability is preserved without disclosure.
- Deliberate `ApiException`s are still echoed — those messages are authored and already sanitised.

Note this makes server logs the only place with failure detail; the reference id is what ties a user
report to the log entry.

## Verification (live, 2026-07-18, after rebuild)

All six data-source failure payloads were replayed and scanned for SQL/schema markers
(`insert into`, `SQL [`, `relation "`, `Failing row`):

- **Zero leaks** across every response (`leaksSql=false` on all six).
- Duplicate key now returns **409** with *"A connection named '…' already exists"* instead of a 500
  carrying the INSERT statement.
- The referenced-delete foreign-key violation likewise returns **409** with a business message and no
  SQL (see [DEF-0019](DEF-0019-datasource-dependency-and-test-audit-gaps.md)).

Because the fix lives in `GlobalExceptionHandler`, it applies to every endpoint — the `/api/policies`
duplicate that originally reproduced the leak is covered by the same change.
