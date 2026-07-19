# DEF-0020 — Connection-test failures are not classified; DNS and timeout are indistinguishable

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | **CLOSED** - rebuilt and live reverified 2026-07-19 |
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

## Live retest and closure

The rebuilt isolated server was tested against disposable H2 and PostgreSQL fault-injection inputs:

| Scenario | Final live result |
|---|---|
| Saved valid H2 source | HTTP 200 with `product: H2` and `elapsedMs: 614` |
| Wrong H2 password through the connection pool | HTTP 400 `[AUTH]`; no secret or stack trace |
| Missing PostgreSQL `.invalid` hostname | HTTP 400 `[DNS]` in 313 ms |
| Refused PostgreSQL port | HTTP 400 `[NETWORK]` in 22 ms |
| Black-holed PostgreSQL endpoint | HTTP 400 `[TIMEOUT]` in 8041 ms |

During the live retest, pool wrappers were found to hide SQLState/root causes in three paths. The final
fix adds narrowly scoped fallbacks for known authentication and network wording, and verifies the
hostname only after a generic connection-attempt failure. Timeout classification remains first at the
configured budget so a slow unresolved local hostname cannot override a real timeout.

`ConnectionFailureClassificationTest` now has 11 passing cases, including the pool-wrapped authentication,
DNS, and refused-port forms. The live evidence is retained in
`docs/testing/evidence/artifacts/DSRC-002-LIVE-2026-07-19.json`.

## Residual

TLS and privilege categories are implemented but remain **HARD-PASS exceptions**: DSRC-002-06/07 need
trusted, untrusted, and hostname-mismatch certificate endpoints, while DSRC-002-08 needs a valid
low-privilege database account. None are provisioned in this environment, so they are not passed or
certified.
