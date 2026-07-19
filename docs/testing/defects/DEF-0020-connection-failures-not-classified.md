# DEF-0020 — Connection-test failures are not classified; DNS and timeout are indistinguishable

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | **FIX WRITTEN** — awaiting rebuild + re-verification |
| Found by story | DSRC-002 — cases 01, 03, 04, 05 |
| Component | `io.forgetdm.datasource.DataSourceService.probe()` |
| Found on | Live stack 2026-07-18 (PostgreSQL lane) |

## Summary

`probe()` performed no classification — every failure was forwarded as the driver's own prose:

```java
catch (Exception e) { throw ApiException.bad("Connection test failed: " + e.getMessage()); }
```

For PostgreSQL that is adequate for authentication and refusal, but **two entirely different faults
produce the identical message**. Measured live:

| Scenario | Elapsed | Message returned |
|---|---|---|
| Auth failure (wrong password) | 190 ms | `FATAL: password authentication failed for user "postgres"` |
| Connection refused (closed port) | 2483 ms | `Connection to localhost:5999 refused. Check that the hostname and port are correct…` |
| **DNS failure** (bad hostname) | **183 ms** | **`The connection attempt failed.`** |
| **Connect timeout** (black-holed IP) | **8179 ms** | **`The connection attempt failed.`** |

The last two are 45× apart in duration and need opposite remediation — *correct the hostname* versus
*open the firewall / fix routing* — yet the operator is told exactly the same thing. Nothing in any
response is machine-readable, so the UI cannot branch on the failure type either.

DSRC-002-04 requires "distinct actionable categories"; DSRC-002-05 requires a timeout to be "recorded
as a timeout rather than generic success/failure". Both fail.

Separately, DSRC-002-01 requires the success result to carry **elapsed time**; the response contained
only `{ok, product, version}`.

## What was already correct

- **Timeouts are genuinely enforced.** The black-holed endpoint terminated at **8179 ms**, matching
  the documented `FORGETDM_JDBC_LOGIN_TIMEOUT_SECONDS = 8`. The UI does not spin indefinitely and
  resources are released.
- **No secret or stack leakage.** No response echoed the submitted password or exposed driver
  internals (`org.postgresql…`, `Caused by`, `HikariPool`).

## Fix

Added `classify(exception, dataSource, elapsedMs)`, keyed on **SQLState first** (a cross-vendor
standard, so it degrades sensibly on engines other than PostgreSQL) and then the root cause type:

| Category | Trigger |
|---|---|
| `[AUTH]` | SQLState `28xxx` |
| `[DATABASE_MISSING]` | SQLState `3D000` |
| `[PRIVILEGE]` | SQLState `42501` |
| `[TLS]` | `SSLException` / CertPath / "certificate" — states the connection was refused, **not** downgraded |
| `[DNS]` | `UnknownHostException` |
| `[TIMEOUT]` | `SocketTimeoutException`, "timed out", or elapsed ≥ 7.5 s (the login-timeout budget) |
| `[NETWORK]` | `ConnectException` or SQLState `08xxx` |
| `[UNKNOWN]` | anything else |

Each message names the host and the remedial action, e.g.
`[TIMEOUT] No response from 10.255.255.1:5432 within 8179 ms. The host is reachable-but-silent or
blocked by a firewall; check routing, or raise FORGETDM_JDBC_LOGIN_TIMEOUT_SECONDS.`

The category token is prefixed to the message rather than added as a new field, so the HTTP contract
is unchanged and the existing UI error handling keeps working while becoming greppable/branchable.

`probe()` success now also returns `elapsedMs` (DSRC-002-01).

**Host extraction strips credentials.** Messages quote `host:port` only — a URL such as
`//alice:s3cret@dbhost:5432/db` yields `dbhost:5432`, never the userinfo (consistent with
[DEF-0013](DEF-0013-audit-records-raw-jdbc-urls.md)). Covered by `ConnectionFailureClassificationTest`.

## Residual

TLS and privilege categories are implemented but **unverified** — DSRC-002-06/07/08 need trusted and
untrusted certificate endpoints and a low-privilege database account, none of which exist in this
environment. They are recorded as NOT EXECUTED, not as passing.
