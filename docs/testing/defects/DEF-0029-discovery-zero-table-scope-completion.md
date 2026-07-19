# DEF-0029 - Zero-table discovery scope could look complete and leave no rejection audit

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED |
| Found by story | DISC-006 (01, 02, 03, 08) |
| Verified | 2026-07-19 |

## Problem

Discovery rejected empty scopes in one service path, but rejected attempts had no dedicated sanitized audit event. A stale or malformed async response with `COMPLETED`, `0/0`, and `100%` could also be displayed as a successful discovery run in the live UI.

## Resolution

`DiscoveryService` now identifies explicit missing or invisible schemas, empty schemas, Flyway-only schemas, and missing focused tables as typed scope validation failures. Synchronous rejection records `DISCOVERY_SCAN_REJECTED`; async preflight records `DISCOVERY_JOB_REJECTED` before any job is created. Both records keep only a safe schema token, source ID, and reason code.

The PII live board now converts a legacy zero-table completed payload into `FAILED` at `0%`, with an actionable rejected-scan message.

## Verification

The isolated H2 contract covers empty, Flyway-only, missing-schema, missing-focus, sync audit, async no-job history, and audit sanitization. The frontend behavior probe directly exercises the zero-table completed presentation.

Evidence: `docs/testing/evidence/DISC-006-EVIDENCE.md`.
