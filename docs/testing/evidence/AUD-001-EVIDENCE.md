# AUD-001 — Material Action Audit Coverage — Evidence

**Story:** AUD-001 (P0, All lanes)
**Spec:** `docs/testing/cases/ready/AUD-001.md`
**Execution status:** **FAIL → substantially remediated.** First pass 2026-07-18: 3 PASS / 5 FAIL / 2 NOT EXECUTED.
After fixes and live re-verification: **6 PASS / 2 PARTIAL / 2 NOT EXECUTED**, with 5 defects closed.
Story remains `status:blocked` pending DEF-0010 completion, cases 02/03, the coverage matrix,
second-reviewer sign-off, and an operator-approved historical-chain re-anchor. DEF-0015 has been
re-verified and closed.

> **This story was previously marked "✅ PASSED (2026-07-17)".** That claim is withdrawn — see
> [DEF-0014](../defects/DEF-0014-aud-001-false-pass-claim.md). The cited evidence
> (`test-results/AUD-001-execution-report.md`) is a generic `mvn test` summary of 228 unrelated unit
> tests (`CopybookCodecTest`, `GeneratorsTest`, …) and exercises none of AUD-001's ten cases.

## Run metadata

| Field | Value |
|---|---|
| Environment | Live local stack — FE `http://localhost:3000`, BE `http://localhost:8088` (post-V61 build) |
| Executed | 2026-07-18 |
| Method | Real HTTP against the running backend; source corroboration for root causes |
| Trail size at execution | ~3,010 events, 95 distinct actions, 13 actors, 10 categories |

## Result summary

| # | Case | Result | Evidence |
|---|---|---|---|
| 01 | CRUD coverage | **FAIL (partial)** | Create/update/delete *are* recorded (`POLICY_CREATED`, `DATASOURCE_UPDATED`, `DATASOURCE_DELETED`, `SECURITY_USER_*`) with actor/action/outcome/timestamp. But **resource identity is absent**: of 3,010 events only **2** have `resourceType`/`resourceId`/`resourceName`, and **0** have metadata/correlation context. `?resourceType=policy` returns **0**. → [DEF-0010](../defects/DEF-0010-audit-events-lack-resource-identity.md) |
| 02 | Execution lifecycle | **NOT EXECUTED** | Requires driving synthetic/provision/mapping/discovery/mainframe jobs to completion, failure, retry and cancel. Deferred — heavy writes previously saturated the backend. |
| 03 | Governance | **NOT EXECUTED** | Requires a full maker/checker approve/reject/promote cycle. Deferred. |
| 04 | Export events | **FAIL** | `GET /api/audit/export.csv` exported the whole trail and produced **no audit event** (total 3012 → 3012; no `*EXPORT*` action exists among the 95). Bulk evidence extraction is untracked. → [DEF-0012](../defects/DEF-0012-audit-export-not-audited.md) |
| 05 | Denial | **FAIL** | Access denial is audited correctly (`ACCESS_DENIED`, outcome FAILURE, severity CRITICAL — verified in RBAC-001/002). **Invalid login is never recorded**: a bad password returned 401 but `LOGIN_FAILED` count stayed 0, and no failed-auth action exists among the 95 distinct actions. → [DEF-0008](../defects/DEF-0008-failed-login-audit-rolled-back.md) |
| 06 | Search / facets / stats | **PASS** | `actor`, `action`, `outcome`, `from` filters each returned only matching rows; ordering strictly descending by seq with **no overlap** across pages (p0 3046→2995, p1 starts 2994); facets 95 actions / 10 categories / 13 actors; `stats.total` agrees with search total. |
| 07 | Export limits | **FAIL** | `export.csv` hard-caps at `PageRequest.of(0, 5000)` and emits **no truncation indicator** (no header, no marker, no total). Below the cap today (3,011 rows exported), but past 5,000 a partial export is indistinguishable from a complete one — precisely what this case forbids. → [DEF-0011](../defects/DEF-0011-audit-csv-silent-truncation.md) |
| 08 | Integrity | **FAIL** | `/api/audit/verify` → `valid:false`, `brokenAtSeq:702`, `hashedCount:11` of 3,005. The chain has **forked**: seq **702 and 703 each exist twice**, both branches sharing prevHash `Tp4bBLlfy28C…`. Verification `break`s at the first mismatch, so ~3,000 events — including every current security event — are **never verified**. → [DEF-0009](../defects/DEF-0009-audit-chain-forked-and-verify-aborts.md) |
| 09 | Leakage | **PASS (with hardening risk)** | Scanned all 3,010 events (1.48 MB): **no** passwords, API tokens, bearer headers, session cookies, SSN patterns or emails. However 14 events embed **raw JDBC URLs** (`jdbc:postgresql://localhost:5433/sourcedb`, `jdbc:oracle:thin:@…`), one with a query string. No credentials present today (`user=` 0, `//user:pass@` 0), but the URL is logged verbatim so a credential-bearing URL would be persisted in clear. → [DEF-0013](../defects/DEF-0013-audit-records-raw-jdbc-urls.md) |
| 10 | Authorization | **PASS** | `rbac_engineer` (no `audit.read`) → **403** on both `/api/audit` and `/api/audit/export.csv`. `rbac_auditor` (has `audit.read`) → **200** on both. |

## Key root causes (source-corroborated)

**DEF-0008 — failed-login audit is rolled back.** The code *does* call
`audit.log(username, "LOGIN_FAILED", …)` (`AccessControlService:151`), but `login()` is
`@Transactional` (`:138`) and the next statement throws `ApiException`. The RuntimeException rolls
back the very transaction the audit row was written in, so the event never persists. This is
invisible to code review and only surfaces live. It also advances the in-memory `lastSeq`/`lastHash`
while discarding the row — which explains the sequence gaps observed (`maxSeq` 3046 vs 3,010 total)
and leaves the next event chaining onto a hash that does not exist in the database.

**DEF-0009 — the chain can fork.** `AuditService.chainAndSave()` allocates the sequence in memory
(`++lastSeq`) guarded only by `synchronized`, which is per-JVM, and there is no unique constraint on
`seq`. Two instances (or a double start) allocate the same number, producing two rows with identical
`seq` and identical `prevHash` — exactly what is present at seq 702/703. `verifyChain()` then
`break`s at the first mismatch and reports a permanent `valid:false`, so an operator cannot
distinguish a structural fork from real tampering, and tampering *after* the break is undetectable.

## Re-verification after fixes (2026-07-18, post-V62 rebuild)

| Case | Was | Now | Evidence |
|---|---|---|---|
| 04 Export events | FAIL | **PASS** | `AUDIT_EXPORTED` recorded before delivery: `resourceType=audit`, `resourceName=audit-trail.csv`, `detail="Exported 3016 of 3016 matching events (csv)"`, metadata `{format,exported,matched,limit,truncated}`. Count 0 → 1. |
| 05 Denial | FAIL | **PASS** | Bad password → `LOGIN_FAILED` persisted (0 → 1), actor `alpha_user`, outcome `FAILURE`, severity `CRITICAL`. HTTP still 401. |
| 07 Export limits | FAIL | **PASS** | `X-Total-Count: 3016`, `X-Exported-Count: 3016`, `X-Export-Limit: 5000`, `X-Truncated: false`; `# TRUNCATED` row appended when capped. |
| 08 Integrity | FAIL | **PARTIAL** | Verified events **11 → 2,345**; walk reaches seq **3,052** (was 702); 10 breaks reported with seq + reason; 10 valid segments. Chain no longer aborts. Still `valid:false` — 7 genuine historical `LINK_BREAK`s plus 3 false `CONTENT_MISMATCH` now tracked as [DEF-0015](../defects/DEF-0015-audit-hash-timestamp-rounding.md). |
| 09 Leakage | PASS (risk) | **PASS (hardened)** | Credential-bearing URL recorded as `jdbc:postgresql://dbhost:5432/salesdb?user=***&password=***&ssl=true` (68 chars vs 104 raw) — userinfo and both credential params removed, host/db/benign param kept. |
| 01 CRUD | FAIL | **PARTIAL** | Resource identity now populated on policy/ACCESS_DENIED/export events (`resourceType=policy`, `resourceId=37`, `resourceName=…`) and the `resourceType=policy` filter returns results (was 0). Remaining ~90 actions still use the legacy 3-arg `log()` → DEF-0010 stays open. |

**DEF-0015 re-verified and closed (2026-07-18).** After the millisecond-truncation fix: total events
3,013 → 3,058 (+45) and `verifiedCount` 2,345 → 2,390 (+45) — every post-fix event verified, with
`contentBreaks` and `linkBreaks` both unchanged. The break list is identical, so all 10 remaining
breaks predate the fix. `valid:false` now reflects genuine historical damage rather than a false
alarm; clearing it needs the operator-acknowledged re-anchor from DEF-0009, deliberately not done
here because rewriting hashes to force a green check would defeat the control.

**Follow-on defect found and closed.** Making verification walk the whole trail exposed 3
`CONTENT_MISMATCH` rows and a `tamperSuspected:true` flag on an untampered database. Root cause is a
precision bug, not tampering: the hash covers `createdAt.toEpochMilli()` while Postgres stores
microseconds and the nano→micro conversion rounds, so ~0.13% of rows re-read one millisecond later
than they were hashed. Fixed by truncating to milliseconds before hashing/persisting
([DEF-0015](../defects/DEF-0015-audit-hash-timestamp-rounding.md), verified live and closed). A false tamper
alarm is the worst outcome for this control, so this is treated as HIGH.

**Defects closed:** DEF-0008, DEF-0009, DEF-0011, DEF-0012, DEF-0013, DEF-0015.
**Still open:** DEF-0010 (partial), DEF-0014 (process).

## Verdict

AUD-001 does **not** pass. Its exit criteria require "complete coverage, valid hash chain, and zero
secret/clear-PII leakage". Export and failed-auth auditing now pass, and resource identity is present
on the hardened paths, but coverage remains incomplete across legacy actions and cases 02/03 have not
been executed. The historical chain remains invalid by design until an operator-approved re-anchor.
Leakage is clean.

## Exit checklist

- [x] DEF-0008, DEF-0009 (HIGH) fixed and re-verified.
- [ ] DEF-0010 completed; DEF-0011 and DEF-0012 are closed.
- [x] DEF-0013 (LOW) hardening.
- [x] DEF-0015 false tamper alarm fixed and verified live.
- [ ] AUD-001-02 and AUD-001-03 executed (job lifecycle + governance).
- [ ] Coverage matrix built mapping every material controller action to its expected event.
- [ ] Second reviewer sign-off; story stays `status:blocked`.

## V62 first-boot verification (2026-07-18)

The controlled first boot was observed against the local PostgreSQL metadata database before any
later migration was allowed to establish success. Full sanitized evidence is recorded in
[`test-results/AUD-001-V62-regression-2026-07-18.md`](../../../test-results/AUD-001-V62-regression-2026-07-18.md).

- Flyway recorded version 62 (`audit sequence integrity`) with `success=true`.
- The audit row count remained 3,012; no row was deleted.
- Original rows kept seq 702/703; duplicate row IDs 704/705 moved to 3050/3051.
- Duplicate sequence groups and null sequence rows both became zero.
- `uq_audit_events_seq` and `audit_event_seq` were present; the next allocation was 3052.
- After normal activity reached 3,081 rows / seq 3120, duplicate sequence groups remained zero.
- `AuditHashChainTest` plus `FlywayMigrationVersionTest`: 7 tests passed, 0 failed.
