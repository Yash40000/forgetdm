# DISC-007 - Idempotent Discovery Rescan Evidence

**Verdict:** PASS - 10/10 required cases directly proven on 2026-07-22.

## Environment

- ForgeTDM backend: isolated local instance on `http://localhost:8099`
- Backend database: PostgreSQL 17 on port 5433 (`forgetdm`)
- Source fixture: PostgreSQL 17 `sourcedb.disc007_acceptance`, data source ID 10
- Browser: Microsoft Edge through Playwright, isolated Next.js frontend on port 3000
- Fixture data: generated, namespaced test-only records; no customer data or clear retained PII

## Case Reconciliation

| Case | Result | Direct proof |
|---|---|---|
| DISC-007-01 | PASS | Two unchanged scans retained six unique physical-column rows with stable IDs and no `uq_class` failure. |
| DISC-007-02 | PASS | Source evidence changed from SSN-shaped to email-shaped; the existing row ID was retained and refreshed to EMAIL. |
| DISC-007-03 | PASS | APPROVED status, TOKENIZE override, and analyst parameter survived changed source data and rescan. |
| DISC-007-04 | PASS | REJECTED and manual APPROVED rows retained IDs/statuses with exactly one row per physical column. |
| DISC-007-05 | PASS | Removing only one machine signal deleted only that stale SUGGESTED row; reviewed rows remained. |
| DISC-007-06 | PASS | EMAIL-only scan preserved the excluded SSN classification while excluding it from scoped scan/results. |
| DISC-007-07 | PASS | Focused `scope_a` scan returned only that table and left `scope_b` ID/timestamp unchanged. |
| DISC-007-08 | PASS | Two concurrent authenticated direct scans returned `200,200`; all post-run physical keys were unique. |
| DISC-007-09 | PASS | Scoped PostgreSQL trigger forced a later write failure; the whole rescan rolled back and clean rerun succeeded. |
| DISC-007-10 | PASS | API and CSV projection counts matched with zero duplicate keys; Edge rendered every API row and downloaded the same records. |

## Retained Artifacts

- Entry gate: `docs/testing/evidence/artifacts/DISC-007-ENTRY-GATE-2026-07-21.json`
- Ten-case PostgreSQL/API run: `docs/testing/evidence/artifacts/DISC-007-LIVE-2026-07-22.json`
- Edge JSON report: `docs/testing/evidence/artifacts/DISC-007-PLAYWRIGHT-2026-07-22.json`
- Edge JUnit report: `docs/testing/evidence/artifacts/DISC-007-PLAYWRIGHT-2026-07-22.xml`
- Final findings workspace: `docs/testing/evidence/artifacts/DISC-007-FINDINGS-UI-2026-07-22.png`
- Repeatable runners: `docs/testing/run-disc-007-acceptance.ps1` and
  `frontend/e2e/disc-007/discovery-rescan-ui.spec.ts`

## Regression Gates

- Focused discovery tests: 13 tests passed, zero failures/errors.
- Complete backend regression: 101 suites, 503 tests, zero failures, zero errors, one unrelated
  opt-in synthetic scenario-pack skip.
- Frontend production build: compiled, TypeScript checked, and all 28 static routes generated.
- Edge UI/CSV lane: 1/1 passed in 29.8 seconds.

## Review

The acceptance table was reconciled against the live JSON, browser JSON/JUnit, screenshot, runner
source, transaction fix, and complete regression output. No criterion relies on source inspection
alone, and no DISC-007 item is deferred or classified HARD-PASS. DEF-0033 is closed.
