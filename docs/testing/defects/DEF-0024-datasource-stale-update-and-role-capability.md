# DEF-0024 - Data-source stale updates and role misuse were not blocked

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED - focused and live verification passed |
| Found by story | DSRC-001-04, DSRC-001-07 |
| Component | Data-source lifecycle, discovery, and provisioning preflight |
| Found | 2026-07-18 live execution and 2026-07-19 contract review |

## Impact

Data-source updates had no version token, so an older browser session could silently overwrite a
newer connection configuration or credential rotation. Discovery also accepted a TARGET-only
connection, while provisioning could queue work with a SOURCE-only target. Both capability errors
were detected too late or not at all.

## Fix

- Add a JPA `@Version` field backed by migration V64 (`lock_version`).
- Require the current version on update and return a sanitized `409 Conflict` for missing, stale,
  or racing updates.
- Carry the version through the Next.js data-source edit payload.
- Add explicit source-capable and target-capable resolution in `DataSourceService`.
- Reject TARGET-only discovery and SOURCE-only provisioning targets before a scan or job is queued.
- Preserve BOTH as valid for both directions.

## Verification

- Focused Maven suite: **20 tests passed, 0 failed**.
- `DataSourceConcurrencyContractTest` proves missing/stale versions are rejected without saving,
  current-version metadata edits preserve the existing secret, role rules are enforced, and a JPA
  race maps to a sanitized `409` response.
- Existing connection classification, safe-JDBC redaction, and global exception audit tests remain
  green in the same run.
- V64 applied successfully against the live PostgreSQL metadata database and populated `lock_version`.
- A stale session received sanitized `409`; a missing version was rejected; neither overwrote the current row.
- TARGET-only discovery, SOURCE-only target provisioning, and TARGET-only provision-source use were rejected before work was created.
- BOTH completed a live source discovery and passed target preflight.
- Oracle and MySQL independently passed the same optimistic-lock, credential-retention, source-discovery, and target-preflight checks.
- The final frontend type gate also passed as part of AUTH-003.

Retained artifacts:

- `docs/testing/evidence/artifacts/DSRC-001-LIVE-2026-07-19.json`
- `docs/testing/evidence/artifacts/DSRC-001-VENDOR-LIVE-2026-07-19.json`
- `docs/testing/evidence/DSRC-001-CONCURRENCY-2026-07-19.md`
