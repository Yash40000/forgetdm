# MASK-003 - Seeded deterministic replay

**Area:** Masking
**Priority:** P0
**Lane:** Unit, reference engine, and cross-connector checksum
**Execution status:** COMPLETE - 9/9 PASS

## Objective

Prove that an explicit masking seed defines a reproducible masked universe across engines,
process-local engine instances, runs, row contexts, and supported JDBC connectors, while a
different seed creates a detectably different universe.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-003-01 | Repeating the same inputs with the same seed on one engine returns byte-identical values | PASS |
| MASK-003-02 | Fresh engine instances with the same project secret and seed return identical values | PASS |
| MASK-003-03 | A different nonblank seed changes every seed-sensitive reference output without breaking its contract | PASS |
| MASK-003-04 | Null and blank seed select the unseeded project-default universe | PASS |
| MASK-003-05 | Value-based masking is independent of execution order; row-index functions remain stable for the same row context | PASS |
| MASK-003-06 | Governed scripts and lookup registries remain wired after a seeded engine is created | PASS |
| MASK-003-07 | Masking Studio preview, DataScope preview, and provisioning core pass the requested seed to canonical engine normalization | PASS |
| MASK-003-08 | Canonical output checksums are identical across two replays and PostgreSQL, Oracle, and MySQL; a different seed changes the checksum | PASS |
| MASK-003-09 | Focused seed tests and the masking/provisioning compatibility regression remain green | PASS |

## Result

- All 43 masking functions replayed identically for the same seed across fresh and reused engines.
- All seed-sensitive functions changed for the alternate seed; explicitly seed-invariant functions remained stable.
- PostgreSQL, Oracle, and MySQL produced one identical canonical checksum after JDBC round trips.
- No product defect was found. No HARD-PASS was required.

## Execution Rules

- Use synthetic input values only.
- Database writes must use disposable objects or rollback-only transactions.
- Evidence records checksums and counts, never passwords or raw sensitive samples.
- Open a defect for every failed case and mark green only after fix and retest.
