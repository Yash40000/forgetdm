# DSRC-001 - Data-Source Lifecycle

**Priority:** P0

**Lane:** Each bundled connector
**Execution status:** COMPLETE WITH HARD-PASS EXCEPTIONS - PostgreSQL, Oracle, MySQL, and H2 live lanes verified on 2026-07-19.
The PostgreSQL concurrency and role preflight runner passed 9/9, the Oracle/MySQL lifecycle
runner passed 18/18, and the embedded H2 lifecycle runner passed 9/9. DEF-0024 is closed with live evidence. DB2, SQL Server, and Teradata remain
HARD-PASS because no live vendor fixture is available; they are not passed or certified. The story
is closed for this test cycle under its documented certification-status exit rule, without treating
those unavailable lanes as functional passes.
Focused evidence: `docs/testing/evidence/DSRC-001-CONCURRENCY-2026-07-19.md`
Evidence: `docs/testing/evidence/DSRC-001-EVIDENCE.md`

## Objective

Prove that source, target, and dual-role connections can be created, edited, used, and safely deleted without exposing credentials or corrupting dependent artifacts.

## Preconditions

- Reachable PostgreSQL reference database and one lane per bundled connector.
- Valid source-only, target-only, and dual-purpose accounts plus an account with insufficient privileges.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| DSRC-001-01 | Create | Create SOURCE, TARGET, and BOTH connections through UI and `POST /api/datasources`. | Each appears once with correct engine/role/environment; secret is write-only/redacted. |
| DSRC-001-02 | Validate | Submit blank/short/duplicate names, malformed JDBC URL, unsupported role/engine, and missing credentials. | Action is rejected with field-specific sanitized errors; no partial row is persisted. |
| DSRC-001-03 | Edit | Change display metadata without resubmitting the secret, then rotate the secret. | Existing secret remains usable until explicit rotation; new secret works and old secret does not. |
| DSRC-001-04 | Role | Attempt discovery from TARGET-only and provision to SOURCE-only. | Capability rules prevent invalid use before job launch; BOTH supports both directions. |
| DSRC-001-05 | Read | Call list/get as authorized reader. | Password/secret/token fields are absent or redacted from every response and browser cache. |
| DSRC-001-06 | Dependency | Delete an unused connection, then attempt to delete one referenced by policy/blueprint/job. | Unused deletion succeeds; referenced deletion is blocked with dependency names or uses documented safe cascade behavior. |
| DSRC-001-07 | Concurrent | Update the same connection from two sessions. | Conflict policy is explicit; a stale update cannot silently overwrite a newer secret/configuration. |
| DSRC-001-08 | Audit | Review create/update/test/delete events. | Actor, connection ID/name, role, engine, and outcome are recorded without URL password or secret. |
| DSRC-001-09 | Authorization | Repeat writes as read-only role. | UI action is unavailable and direct API receives `403`; data is unchanged. |

## Automation and Exit

- Add controller/service integration coverage and a connector contract suite reused by every certified engine.
- Pass requires all bundled lanes or a documented unsupported/certification status, never a false success.
