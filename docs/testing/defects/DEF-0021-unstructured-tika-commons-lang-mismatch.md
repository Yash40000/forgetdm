# DEF-0021 - Tika runtime dependency mismatch fails unstructured masking before cancellation

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | **CLOSED - fixed and verified live 2026-07-18** |
| Found by story | AUD-001-02 |
| Component | Unstructured masking content detection |
| Found in run | `20260718173719` |

## Reproduction

1. Submit a 4.14 MB text file to `/api/unstructured/jobs`.
2. Request cancellation while the job is at the DETECT stage.
3. Observe job 2 end as `FAILED` at 5 percent instead of `CANCELED`.
4. Observe no `UNSTRUCTURED_JOB_CANCELED` event.

The fail-closed job evidence contained:

`'java.lang.String org.apache.commons.lang3.SystemProperties.getUserName(java.lang.String)'`

No unmasked output was released, but valid files could not be processed and cancellation semantics
were incorrect.

## Root cause

ForgeTDM uses Apache Tika 3.3.1. Its installed parent POM declares Commons Lang 3.20.0. Spring Boot
3.3.5 dependency management selected Commons Lang 3.14.0 instead, so Tika invoked a method absent
from the runtime jar and raised `NoSuchMethodError` during detection.

The worker also checked `cancelRequested` only after detection, delaying an already requested cancel
until after that stage.

## Fix

- Pin `org.apache.commons:commons-lang3:3.20.0` in `pom.xml`, matching Tika 3.3.1.
- Check cancellation before entering content detection.
- Add a dependency-compatibility regression and a deterministic pre-detection cancellation test.

## Verification

- `UnstructuredMaskingServiceTest` and `Auth003FrontendBehaviorTest`: 9 tests passed, 0 failed.
- Full AUD-001 lifecycle run `20260718175317`: 27 checks passed, 0 failed.
- Retest job 4 reached `CANCELED` and emitted `UNSTRUCTURED_JOB_CANCELED` with actor, resource,
  timestamp, and `SUCCESS` outcome.
