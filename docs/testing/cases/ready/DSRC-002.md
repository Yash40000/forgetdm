# DSRC-002 - Connection Test, Timeout, TLS, and Authentication Feedback

**Priority:** P0

**Lane:** Each connector
**Execution status:** **COMPLETE WITH HARD-PASS EXCEPTIONS** - live retested 2026-07-19.
The directly executable local lane is green: saved and transient success, authentication, DNS,
refused-port network failure, timeout, concurrency, and diagnostics all have retained evidence.
DEF-0020 is closed. TLS trust, TLS hostname identity, and a valid low-privilege account remain
HARD-PASS exceptions because their required fixtures are not provisioned; they are not passed or certified.
Evidence: `docs/testing/evidence/DSRC-002-EVIDENCE.md` and
`docs/testing/evidence/artifacts/DSRC-002-LIVE-2026-07-19.json`.

## Objective

Prove that saved and transient connection tests give timely, accurate, sanitized feedback for success and common network, TLS, authentication, and authorization failures.

## Preconditions

- Valid endpoint, closed port, black-holed/timeout endpoint, bad credential, missing database, trusted TLS, untrusted TLS, and hostname-mismatch fixtures.
- Vendor driver and client versions recorded.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| DSRC-002-01 | Success | Test a valid saved connection via `POST /api/datasources/{id}/test`. | UI immediately shows testing state and terminal success with engine/database identity and elapsed time. |
| DSRC-002-02 | Transient | Test unsaved valid settings via `POST /api/datasources/test-connection`. | Result is accurate and no connection row or secret is persisted. |
| DSRC-002-03 | Auth | Test wrong password and locked/expired database account. | Failure is classified as authentication/account failure without echoing password or full driver stack. |
| DSRC-002-04 | Network | Test DNS failure, connection refused, and unreachable route. | Distinct actionable categories are returned within configured timeout; UI never spins indefinitely. |
| DSRC-002-05 | Timeout | Use a black-holed endpoint and cancel/leave the page. | Test terminates at the documented timeout, frees resources, and records a timeout rather than generic success/failure. |
| DSRC-002-06 | TLS trust | Test valid trusted certificate and an untrusted certificate. | Trusted connection succeeds; untrusted connection fails closed with certificate guidance. |
| DSRC-002-07 | TLS identity | Connect using a hostname not covered by the certificate. | Hostname verification fails; there is no silent trust-all fallback. |
| DSRC-002-08 | Authorization | Use valid credentials lacking metadata/table privileges. | Connectivity success is distinguished from capability/readiness failure and lists sanitized missing capabilities. |
| DSRC-002-09 | Concurrency | Launch repeated tests against the same source. | Pool/thread use stays bounded; latest visible result is not overwritten by an older request. |
| DSRC-002-10 | Diagnostics | Compare test result with `/api/datasources/{id}/diagnostics`. | Both surfaces agree on engine and capability status; no false native-loader readiness is reported. |

## Automation and Exit

- Run a fault-injection connector matrix and assert category, timeout, resource cleanup, and secret redaction.
- Pass requires every certified connector to distinguish success, auth, network, timeout, TLS, and privilege outcomes.
