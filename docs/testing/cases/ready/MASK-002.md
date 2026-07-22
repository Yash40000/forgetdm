# MASK-002 - Boundary and datatype-fit behavior

**Area:** Masking
**Priority:** P0
**Lane:** Unit, reference engine, and local SQL assertions
**Execution status:** COMPLETE - 7/7 acceptance cases passed, 2026-07-22

## Objective

Prove that masking preserves intentional null/empty semantics, handles Unicode without
leaking or corrupting characters, remains stable at maximum practical lengths, and
produces values that fit the target JDBC datatype before a database batch is executed.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-002-01 | Every masking function has explicit null and empty-input behavior, with no accidental clear-text substitution | PASS - 43/43 classified and executed |
| MASK-002-02 | Whitespace and lookup-reserved values follow their documented contracts | PASS - direct/hash NULL, empty, and spaces |
| MASK-002-03 | Accented Latin, Greek, Cyrillic, Devanagari, CJK, and supplementary characters remain valid Unicode; maskable letters do not pass through unchanged | PASS - multilingual code-point assertions |
| MASK-002-04 | Long boundary input completes deterministically without stack overflow, heap retention, length drift, or broken surrogate pairs | PASS - deterministic large mixed-Unicode value |
| MASK-002-05 | Integer, decimal, boolean, date, time, timestamp, character, binary, and LOB values are bound using compatible JDBC values or rejected with field context before batching | PASS - 14/14 target-fit and LOB tests |
| MASK-002-06 | Character limits and numeric precision/scale are enforced without splitting Unicode code points or silently sending an invalid string to the driver | PASS - PostgreSQL, Oracle, and MySQL live SQL |
| MASK-002-07 | Focused masking/provisioning tests and the existing masking regression suite remain green | PASS - 108/108 |

## Execution Rules

- Use synthetic boundary values and disposable local database objects only.
- Compact evidence is retained in
  `docs/testing/evidence/artifacts/MASK-002-RESULT-2026-07-22.json`.
- `DEF-0035` and `DEF-0036` were fixed and retested. No case is deferred or HARD-PASS.
