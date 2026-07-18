# DEF-0008 — Failed-login audit events are rolled back and never persist

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | **CLOSED** — fixed and verified live 2026-07-18 |
| Found by story | AUD-001 — case AUD-001-05 |
| Component | `io.forgetdm.security.AccessControlService.login()` + `io.forgetdm.audit.AuditService` |
| Found on | Live stack 2026-07-18 (post-V61 build) |

## Summary

Failed authentication attempts leave **no trace in the audit trail**. The code appears correct —
`AccessControlService:151` calls:

```java
audit.log(username, "LOGIN_FAILED", "Invalid username/password");
throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
```

but `login()` is annotated `@Transactional` (`:138`). The `ApiException` is a `RuntimeException`, so
Spring **rolls back the transaction that the audit INSERT was written in**. The event is discarded.

Verified live: a login with a deliberately wrong password returned `401`, yet the `LOGIN_FAILED`
count stayed at **0**, and no failed-auth action of any name exists among the **95 distinct actions**
in a 3,010-event trail (only `LOGIN_SUCCESS`).

## Impact

- **Brute-force and credential-stuffing attempts are invisible.** There is no record of repeated
  failed logins against any account — a basic expectation of a compliance-grade audit trail, and
  required by AUD-001-05 ("Security/material failures are auditable").
- **Collateral chain damage.** `chainAndSave()` still ran before the rollback, so the in-memory
  `lastSeq` advanced and `lastHash` was set to the hash of a row that was never committed. The next
  committed event therefore chains onto a non-existent hash. This produces the observed sequence
  gaps (`maxSeq` 3046 vs 3,010 rows) and contributes to the broken chain in
  [DEF-0009](DEF-0009-audit-chain-forked-and-verify-aborts.md).
- The same pattern applies to **any** `audit.log(...)` followed by a thrown exception inside a
  transactional service method — i.e. most failure-path auditing across the app is likely affected.

## Steps to reproduce

1. `GET /api/audit?action=LOGIN_FAILED` → note total (0).
2. `POST /api/auth/login` with a valid username and a wrong password → `401`.
3. Repeat step 1 → total still **0**. Confirm via `GET /api/audit/facets` that no failed-login action exists.

## Recommended fix

Audit writes must not share the fate of the business transaction:

- Annotate the audit write with `@Transactional(propagation = Propagation.REQUIRES_NEW)` so it
  commits independently of the caller's rollback. Apply on `AuditService.log/record` (or on an
  inner `chainAndSave`), not on the callers.
- Because the sequence/hash are mutated before the row commits, move the `lastSeq`/`lastHash`
  advance to **after** a confirmed commit, or allocate from the database (see DEF-0009) so a
  rollback cannot desynchronise the in-memory chain state.
- Add a regression test: failed login → assert exactly one persisted `LOGIN_FAILED` event.
- Audit the failure **without** echoing the attempted password (current detail text is safe).

## Resolution (2026-07-18) — verified live

Persistence was moved into a new `io.forgetdm.audit.AuditWriter` annotated
`@Transactional(propagation = REQUIRES_NEW)`, so the audit row commits in its own transaction and is
unaffected by the caller's rollback. `AuditService.chainAndSave()` now delegates to it and swallows
(after logging) any write error, so auditing can never fail the operation it records.

The in-memory `lastSeq`/`lastHash` counters were removed entirely — `prevHash` is now read from the
database inside the write transaction, so a rolled-back write can no longer desynchronise chain state.

**Live re-verification (post-rebuild):**

| Check | Before | After |
|---|---|---|
| `LOGIN_FAILED` events after a bad password | **0** | **1** |
| Event fields | — | actor `alpha_user`, outcome `FAILURE`, severity `CRITICAL` |
| HTTP response | 401 | 401 (unchanged) |

Failed authentication is now recorded and attributable.
