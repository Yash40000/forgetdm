# DSRC-002 - Parallel Evidence Lane - 2026-07-19

**Story:** DSRC-002 - Connection Test, Timeout, TLS, and Authentication Feedback
**Purpose:** Independent post-restart evidence lane
**Execution date:** 2026-07-19
**Scope rule:** This lane does not edit production code, the story file, or shared story/defect indexes.

## Result at a glance

The local application-level checks are green, but DSRC-002 is **not complete**. The live connector evidence is PostgreSQL-only, and the TLS, TLS-identity, and privilege cases remain resource-blocked. DEF-0020 is still **FIX WRITTEN - awaiting live rebuild and re-verification**; its classification code is verified by focused unit tests, but the HTTP/UI behavior has not been retested on a rebuilt server.

## Fresh focused execution

These tests use only local Java and H2 fixtures and require no external vendor, certificate, or low-privilege database fixture.

| Command | Result | Direct evidence |
|---|---|---|
| `mvn -o "-Dtest=ConnectionFailureClassificationTest" test` | BUILD SUCCESS; 9 tests, 0 failures, 0 errors, 0 skipped | `target/surefire-reports/io.forgetdm.datasource.ConnectionFailureClassificationTest.txt` |
| `mvn -o "-Dtest=SafeJdbcRedactionTest,ConnectorDiagnosticsServiceTest" test` | BUILD SUCCESS; 5 tests, 0 failures, 0 errors, 0 skipped | `target/surefire-reports/io.forgetdm.datasource.SafeJdbcRedactionTest.txt`; `target/surefire-reports/io.forgetdm.datasource.ConnectorDiagnosticsServiceTest.txt` |

**Fresh local total:** 14 tests passed, 0 failed, 0 errored, 0 skipped.

The classifier tests directly exercise `[AUTH]`, `[DATABASE_MISSING]`, `[PRIVILEGE]`, `[DNS]`, `[TIMEOUT]`, `[NETWORK]`, and `[TLS]` categories, credential-safe host extraction, elapsed-time-sensitive timeout classification, and secret redaction. The H2 diagnostics fixture directly exercises quoted identifiers, composite keys, composite foreign keys, LOB detection, missing primary keys, and cyclic relationships.

## Case-by-case evidence map

| Case | Required behavior | Current result | Evidence and boundary |
|---|---|---|---|
| DSRC-002-01 | Saved connection succeeds with engine identity, elapsed time, and visible terminal result | **FIX WRITTEN - live retest pending** | Prior live PostgreSQL result succeeded in `DSRC-002-EVIDENCE.md` (341 ms, PostgreSQL 17.10), but the response lacked `elapsedMs`. `DataSourceService.probe()` now adds `elapsedMs`; the current classifier suite compiles against the change. A rebuilt live endpoint has not been retested after restart. |
| DSRC-002-02 | Unsaved connection test does not persist a connection or secret | **VERIFIED** | Prior live PostgreSQL evidence in `DSRC-002-EVIDENCE.md`: connection count stayed 11 -> 11 and no transient row was persisted. No code or data was changed by this lane. |
| DSRC-002-03 | Wrong credentials are categorized as authentication/account failure without secrets or stack details | **FIX WRITTEN - live retest pending** | Prior live test proved sanitization but not a machine-readable category. Fresh `ConnectionFailureClassificationTest` verifies SQLState `28xxx` -> `[AUTH]`; `SafeJdbcRedactionTest` is 4/4 and proves userinfo/query credentials are removed. HTTP/UI category behavior still needs a rebuilt live run. |
| DSRC-002-04 | DNS, refused, and unreachable failures are distinct and actionable within timeout | **FIX WRITTEN - live retest pending** | Prior live evidence recorded refusal as distinct but DNS and black-hole timeout as identical generic failures. Fresh classifier tests verify `[DNS]`, `[NETWORK]`, and `[TIMEOUT]` branches. The live PostgreSQL endpoint has not been rebuilt and rechecked. |
| DSRC-002-05 | Black-hole test ends at configured timeout, releases resources, and is recorded as timeout | **FIX WRITTEN - live retest pending** | Prior live evidence measured 8,179 ms against the 8-second budget and showed no indefinite spin, but the response was generic. Fresh tests verify elapsed-time timeout classification. Live retest must confirm the rebuilt HTTP response exposes `[TIMEOUT]` and the UI terminal state. |
| DSRC-002-06 | Trusted TLS succeeds; untrusted TLS fails closed with guidance | **HARD-PASS - resource blocked** | No trusted and untrusted certificate endpoints are available in this environment. The fresh unit test verifies TLS failure is closed and actionable, but it cannot prove a trusted success or real certificate-chain failure. |
| DSRC-002-07 | Certificate hostname mismatch fails; no trust-all downgrade | **HARD-PASS - resource blocked** | No hostname-mismatch certificate fixture is available. The fresh TLS unit test verifies fail-closed messaging and no downgrade for a handshake failure, but not hostname verification against a live driver. |
| DSRC-002-08 | Valid login with insufficient metadata/table privileges is separated from connectivity success | **HARD-PASS - resource blocked** | No low-privilege database account or vendor privilege fixture is available. SQLState `42501` -> `[PRIVILEGE]` is directly unit-tested, but live capability/readiness behavior remains unverified. |
| DSRC-002-09 | Repeated tests remain bounded and stale results do not overwrite newer results | **VERIFIED - prior live lane** | Prior live PostgreSQL evidence recorded 8 simultaneous tests, all HTTP 200, in 1,287 ms, with healthy follow-up listing and Hikari max 4. This lane did not alter concurrency code. The stale-result UI assertion remains represented by the existing live evidence only; no new browser evidence was created here. |
| DSRC-002-10 | Diagnostics and connection test agree on engine/capability status and do not overstate native readiness | **VERIFIED - prior live lane; local fixture also green** | Prior live PostgreSQL evidence recorded agreement between `/api/datasources/{id}/diagnostics` and connection test. Fresh `ConnectorDiagnosticsServiceTest` passed 1/1 against a local H2 messy-schema fixture and confirms diagnostic shape analysis without schema mutation. Vendor-specific native-loader readiness is not certified by the H2 test. |

## Implementation state versus verification state

The following implementation is present in the current worktree, but is not treated as live proof:

- `DataSourceService.probe()` returns `elapsedMs` on success.
- `DataSourceService.classify(...)` classifies SQLState and root causes into actionable categories.
- TLS errors fail closed and do not silently downgrade.
- Host extraction and diagnostics redact JDBC credentials.

These are **verified at application level** by the fresh 14-test suite. DEF-0020 remains **FIX WRITTEN**, because the story requires live rebuilt HTTP/UI evidence for the changed behavior.

## Exact remaining gaps

1. Rebuild/restart the backend and rerun PostgreSQL cases 01, 03, 04, and 05 through the actual API/UI; capture response category, elapsed time, and terminal UI state.
2. Provision trusted and untrusted TLS endpoints plus a hostname-mismatch certificate and execute cases 06 and 07.
3. Provision a valid database account lacking metadata/table privileges and execute case 08.
4. Repeat the matrix for every connector that is claimed as certified. The current live evidence certifies PostgreSQL only; JDBC driver presence alone is not vendor certification.
5. Add retained browser evidence for the concurrency stale-result assertion if the acceptance gate requires browser-level proof rather than the existing live API run.

## Changed files in this lane

Only this file was added by the DSRC-002 parallel lane:

- `docs/testing/evidence/DSRC-002-PARALLEL-2026-07-19.md`

No production source, story file, defect file, defect index, story board, or shared test catalog was edited in this lane.

## Exit decision

**DSRC-002 remains OPEN.** The local app-level behavior is green and the prior PostgreSQL live evidence is retained, but the story cannot be marked complete until DEF-0020 is live-reverified and cases 06, 07, and 08 have real fixtures. The three unavailable-resource cases are explicitly recorded as HARD-PASS, not as passes.
