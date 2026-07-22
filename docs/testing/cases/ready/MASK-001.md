# MASK-001 - Built-in masking function contracts

**Area:** Masking
**Priority:** P0
**Lane:** Unit and reference engine
**Execution status:** COMPLETE - 5/5 acceptance cases passed, 2026-07-22

## Objective

Prove that every masking function exposed by ForgeTDM has a maintained valid reference
configuration and that malformed constrained parameters fail before a masking job starts.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-001-01 | The maintained matrix contains every `MaskFunction` enum value exactly once | PASS - 43/43 |
| MASK-001-02 | Every function passes policy preflight and executes its valid reference case | PASS - 43/43 |
| MASK-001-03 | Every constrained function rejects a representative malformed configuration; parameter-free functions are explicit | PASS |
| MASK-001-04 | Masking, policy, script, lookup, mainframe, Temenos, and unstructured compatibility suites remain green | PASS - 73/73 |
| MASK-001-05 | Masking Studio renders the complete catalog, previews a valid function, and displays invalid-parameter feedback | PASS - Edge UI, 43 cards |

## Automation

- `MaskingFunctionCatalogAcceptanceTest` is the exhaustive catalog gate.
- The focused compatibility command and result are recorded in
  `docs/testing/evidence/artifacts/MASK-001-RESULT-2026-07-22.json`.
- `DEF-0034` was fixed and retested. No case is deferred or HARD-PASS.
- The retained production-like UI on port `3011` passed the Playwright catalog and preview gate.
