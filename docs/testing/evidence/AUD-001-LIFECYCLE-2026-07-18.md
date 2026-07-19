# AUD-001 Lifecycle Evidence - 2026-07-18

## Scope

This run proves success, failure, retry, cancellation, and structured audit attribution for the
mapping, provisioning, synthetic, unstructured, and mainframe execution engines. It does not by
itself prove discovery, governance, export, search, integrity, leakage, or authorization cases.

## Environment

| Field | Value |
|---|---|
| Backend | Local ForgeTDM build on `http://localhost:8131` |
| Management | `http://localhost:8298` (`UP`) |
| Metadata database | Local PostgreSQL on port 5433, Flyway version 63 |
| Runner | `docs/testing/run-aud-001-lifecycle.ps1` |
| Final run ID | `20260718175317` |
| Final result | **27 PASS / 0 FAIL** |

## Defect discovery and retest

The first corrected run (`20260718173719`) passed 25 of 27 checks. Unstructured cancellation ended
as `FAILED` at 5 percent and the cancel audit event was absent. Job 2 recorded:

`'java.lang.String org.apache.commons.lang3.SystemProperties.getUserName(java.lang.String)'`

The installed Tika 3.3.1 parent POM requires Commons Lang 3.20.0, while the effective dependency
tree selected 3.14.0 through Spring Boot dependency management. DEF-0021 pins 3.20.0 and checks the
cancel flag before content detection. Targeted regression tests then passed, followed by this full
live rerun.

## Final lifecycle results

| Engine/path | Evidence | Result |
|---|---|---|
| Mapping failure | run 8 reached `FAILED` | PASS |
| Mapping retry | run 9 reached `COMPLETED` | PASS |
| Mapping cancel | run 10 reached `CANCELED` | PASS |
| Provision complete | job 112, 25 rows, `COMPLETED` | PASS |
| Provision fail and retry | job 113 `FAILED`; retry 114 loaded 25 rows and `COMPLETED` | PASS |
| Provision cancel | job 115 reached `CANCELED` | PASS |
| Synthetic complete | job `ab75464f-a158-4a5e-82a5-668073e24161` reached `COMPLETED` | PASS |
| Synthetic cancel | job `17b8f34e-8618-458a-b73f-f194081c2adb` reached `CANCELLED` | PASS |
| Unstructured complete | job 3 reached `COMPLETED`, 2 findings | PASS |
| Unstructured cancel | job 4 reached `CANCELED` | PASS |
| Mainframe cancel | job 3 reached `CANCELED` | PASS |
| Mainframe retry | job 4 reached `COMPLETED`, 200,000 records | PASS |

## Audit assertions

All 15 asserted terminal/retry events were found with actor `admin`, the expected success/failure
outcome, resource type, resource ID, resource name, and timestamp:

- `MAPPING_RUN_FAILED`, `MAPPING_RUN_RETRIED`, `MAPPING_RUN_COMPLETED`, `MAPPING_RUN_CANCELED`
- `PROVISION_JOB_COMPLETED`, `PROVISION_JOB_FAILED`, `PROVISION_JOB_RETRIED`, `PROVISION_JOB_CANCELED`
- `SYNTHETIC_JOB_COMPLETED`, `SYNTHETIC_JOB_CANCELLED`
- `UNSTRUCTURED_JOB_COMPLETED`, `UNSTRUCTURED_JOB_CANCELED`
- `MAINFRAME_JOB_CANCELED`, `MAINFRAME_JOB_RETRIED`, `MAINFRAME_JOB_COMPLETED`

The runner contains no database password or payload-row assertion output. It uses unique disposable
artifact names per run and now writes complete JSON evidence to
`test-results/AUD-001-lifecycle-<runId>.json` on every execution.

## Commands and automated regression

```powershell
mvn -q "-Dtest=UnstructuredMaskingServiceTest,Auth003FrontendBehaviorTest" test
powershell -NoProfile -ExecutionPolicy Bypass -File docs/testing/run-aud-001-lifecycle.ps1 -Password <redacted>
```

Targeted regression result: 9 tests passed, 0 failed. Final live result: 27 checks passed, 0 failed.
