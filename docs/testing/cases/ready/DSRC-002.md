# DSRC-002 - Connection Test, Timeout, TLS, and Authentication Feedback

**Priority:** P0

**Lane:** Each connector
**Execution status:** EXECUTED LIVE 2026-07-18 (**PostgreSQL lane only**) — 4 PASS / 3 PARTIAL / 3 NOT EXECUTED.
Key finding: DNS failure and connect timeout return the **identical** message (183 ms vs 8179 ms) →
DEF-0020 (fix written, awaiting rebuild). Timeouts themselves are correctly enforced at the documented
8 s budget, and no secret or driver stack leaks. TLS (06/07) and privilege (08) **NOT EXECUTED** — no
fixtures; recorded as uncertified, not passed.
Evidence: `docs/testing/evidence/DSRC-002-EVIDENCE.md`

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
