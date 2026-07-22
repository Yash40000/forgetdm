# MASK-005 - Derived full-name row consistency

**Area:** Masking
**Priority:** P0
**Lane:** Reference engine and provisioning execution-order contract
**Execution status:** COMPLETE - 8/8 PASS

## Objective

Prove that first name and last name are masked once per row and that a derived full-name rule composes
those already-masked sibling values, independent of physical column order, without changing the
independent full-name or null contracts.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-005-01 | FIRST_NAME and LAST_NAME remain deterministic semantic masks | PASS |
| MASK-005-02 | `FIRST LAST` equals the already-masked first and last sibling values | PASS - DEF-0038 fixed |
| MASK-005-03 | `LAST, FIRST` and output-case options compose the same sibling values exactly | PASS |
| MASK-005-04 | Common prefixed aliases such as `customer_fname` and `customer_surname` are recognized deterministically | PASS |
| MASK-005-05 | Name components execute before FULL_NAME and NAME_SAFE EMAIL even when physical target order is reversed | PASS - DEF-0038 fixed |
| MASK-005-06 | Copy, keyed in-place, and rebuild in-place paths preserve writer order while retaining masked row context | PASS - shared ordering contract wired into all paths |
| MASK-005-07 | DataScope preview and Business Entity capsule masking use the same component-before-derived behavior | PASS |
| MASK-005-08 | Independent FULL_NAME fallback and null preservation remain intact; full backend regression stays green | PASS - 541 tests, 0 failures, 0 errors |

## Result

- Forty-three focused masking and ordering tests passed.
- Complete backend regression passed 541 tests with zero failures and zero errors; six unrelated
  environment-conditional tests remained skipped.
- DEF-0038 was reproduced, fixed, and retested.
- No HARD-PASS was required.

## Execution Rules

- Use synthetic names only.
- Compare exact composed values, not only shape or non-null status.
- Keep physical writer column order unchanged while changing evaluation order.
- Preserve null and independent FULL_NAME behavior.
