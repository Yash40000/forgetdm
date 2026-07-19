# DSRC-002 — Connection Test, Timeout, TLS, and Authentication Feedback — Evidence

**Story:** DSRC-002 (P0, per connector)
**Spec:** `docs/testing/cases/ready/DSRC-002.md`
**Execution status:** EXECUTED LIVE 2026-07-18 — **4 PASS / 3 PARTIAL (fix written) / 3 NOT EXECUTED**.

## Run metadata

| Field | Value |
|---|---|
| Environment | Live local stack — new UI `http://localhost:3000` → backend `:8088` |
| Executed | 2026-07-18 |
| Engine under test | PostgreSQL 17.10 (driver: pgjdbc) |
| Timeout configuration | `FORGETDM_JDBC_LOGIN_TIMEOUT_SECONDS=8`, `READ=45`, Hikari pool max 4 |
| Lane coverage | **PostgreSQL only** — other bundled engines not exercised; recorded as *not certified*, never as pass |

## Fault-injection matrix (live measurements)

| Scenario | Elapsed | Response | Distinct? |
|---|---|---|---|
| Valid saved connection | 341 ms | `{ok:true, product:"PostgreSQL", version:"17.10"}` | ✓ |
| Auth failure (wrong password) | 190 ms | `FATAL: password authentication failed for user "postgres"` | ✓ |
| Connection refused (closed port 5999) | 2483 ms | `Connection to localhost:5999 refused. Check that the hostname and port are correct…` | ✓ |
| DNS failure (`.invalid` host) | **183 ms** | `The connection attempt failed.` | ✗ |
| Connect timeout (black-holed `10.255.255.1`) | **8179 ms** | `The connection attempt failed.` | ✗ **identical to DNS** |

## Result summary

| # | Case | Result | Evidence |
|---|---|---|---|
| 01 | Saved-connection success | **PARTIAL → [DEF-0020](../defects/DEF-0020-connection-failures-not-classified.md)** | Returns engine identity (`PostgreSQL 17.10`) and completes in 341 ms, but carries **no elapsed time**, which the case requires. Fix adds `elapsedMs`. |
| 02 | Transient (unsaved) test | **PASS** | `POST /api/datasources/test-connection` ran against unsaved settings; connection count unchanged **11 → 11** and no row named `TRANSIENT-SHOULD-NOT-PERSIST` exists. No secret persisted. |
| 03 | Auth failure feedback | **PARTIAL → DEF-0020** | Correctly identified and **sanitised** — the submitted password was not echoed and no driver internals (`org.postgresql`, `Caused by`, `HikariPool`) appeared. But there is no machine-readable category. |
| 04 | Network categories | **FAIL → DEF-0020** | Refusal is distinct and actionable, but **DNS failure and connect timeout return the identical string** despite being 183 ms vs 8179 ms and needing opposite remediation. Not "distinct actionable categories". |
| 05 | Timeout behaviour | **PARTIAL → DEF-0020** | The timeout itself is **correctly enforced**: the black-holed endpoint terminated at **8179 ms**, matching the documented 8 s budget — no indefinite spin, resources released. But it is reported as a generic failure, not "recorded as a timeout". |
| 06 | TLS trust (trusted vs untrusted cert) | **NOT EXECUTED** | No trusted/untrusted TLS endpoints available in this environment. Not claimed either way. |
| 07 | TLS hostname identity | **NOT EXECUTED** | No certificate with a mismatched hostname available. A `[TLS]` category was implemented defensively (fails closed, never downgrades) but is **unverified**. |
| 08 | Privilege vs connectivity | **NOT EXECUTED** | Requires a database account lacking metadata/table privileges; none available. |
| 09 | Concurrency | **PASS** | 8 simultaneous tests against one source: **all 200**, completed in 1287 ms total, and the backend stayed healthy immediately afterwards (list 694 ms). Pool use stayed bounded (Hikari max 4). |
| 10 | Diagnostics agreement | **PASS** | `/api/datasources/2/diagnostics` returns `readinessScore, status, connection, capabilities, schemaShape, issues, inspectedAt` and agrees with the test on engine (PostgreSQL). No false native-loader readiness observed. |

## Observation worth recording

During the first (batched) run, several concurrent probes against unreachable endpoints degraded the
whole backend by roughly **10×** (login 4051 ms vs a ~610 ms baseline; list 640 ms vs ~50 ms). It
recovered fully once the probes drained, and the follow-up concurrency test (case 09) against a
*healthy* source stayed bounded and fast. The likely cause is threads parked on DNS/TCP for up to the
8 s budget rather than a pool leak — `openPooled()` correctly falls back to an unpooled connection for
unsaved definitions, so transient tests do not create pools. Recorded because a burst of failing
connection tests transiently degrading the console is worth knowing; it is not a leak.

## Defects raised

| ID | Severity | Summary |
|---|---|---|
| [DEF-0020](../defects/DEF-0020-connection-failures-not-classified.md) | MEDIUM | Failures not classified; DNS and timeout indistinguishable; success lacks elapsed time |

## Exit checklist

- [ ] Rebuild and re-verify DEF-0020 — expect `[DNS]` vs `[TIMEOUT]` to separate, and `elapsedMs` on success.
- [ ] Provision TLS fixtures (trusted, untrusted, hostname-mismatch) to execute 06/07.
- [ ] Provision a low-privilege database account to execute 08.
- [ ] Certify or explicitly mark unsupported each non-PostgreSQL lane — the story's exit rule forbids a false success.
