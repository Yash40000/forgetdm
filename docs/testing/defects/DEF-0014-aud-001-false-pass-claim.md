# DEF-0014 — AUD-001 marked PASSED on evidence that tests none of its acceptance criteria

| Field | Value |
|---|---|
| Severity | MEDIUM (process / QA integrity) |
| Status | **OPEN** |
| Found by story | AUD-001 (meta) |
| Component | `docs/testing/cases/ready/AUD-001.md`, `test-results/AUD-001-execution-report.md` |

## Summary

AUD-001 (P0) was marked **"✅ PASSED (2026-07-17)"** citing
`test-results/AUD-001-execution-report.md`. That report is a generic `mvn test` summary — 228 unit
tests across `CopybookCodecTest`, `GeneratorsTest`, `MaskingEngineTest`, `SubsetLogicTest` and
similar — and exercises **none** of AUD-001's ten cases:

| AUD-001 case | Covered by the cited report? |
|---|---|
| 01 CRUD audit coverage | No |
| 02 Execution lifecycle | No |
| 03 Governance maker/checker | No |
| 04 Export events | No |
| 05 Denial auditing | No |
| 06 Search/facets/stats | No |
| 07 CSV export limits | No |
| 08 Hash-chain integrity + tamper | No |
| 09 Secret/PII leakage | No |
| 10 Authorization (403) | No |

The report's "Coverage Analysis" asserts "✅ Service layer tests for audit operations", but no
`AuditServiceTest` appears in its own suite list. It also records "Java Version: OpenJDK 17" while the
application runs on Java 21.0.11.

Live execution on 2026-07-18 found AUD-001 **fails 5 of 10 cases**, including a permanently invalid
hash chain ([DEF-0009](DEF-0009-audit-chain-forked-and-verify-aborts.md)) and completely unrecorded
failed logins ([DEF-0008](DEF-0008-failed-login-audit-rolled-back.md)) — so the pass claim was not
merely unsupported, it was wrong.

## Impact

A P0 compliance story carried a green status while two HIGH defects sat undetected in the feature it
was meant to certify. If other stories were closed the same way, the board's status is unreliable.

## Recommended fix

- Status reset to `FAIL`/`blocked` with real evidence: `docs/testing/evidence/AUD-001-EVIDENCE.md`. **(done)**
- Retain `test-results/AUD-001-execution-report.md` as a *unit-test run record*, renamed/relabelled so
  it is not mistaken for AUD-001 acceptance evidence.
- Enforce the rule already written in `docs/testing/cases/ready/README.md`: a pass claim must attach
  per-case evidence and be reviewed by a second person. A unit-test summary is not case evidence.
- Re-audit any other story currently marked passed for the same substitution.
