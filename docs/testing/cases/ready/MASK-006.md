# MASK-006 - Independent full-name masking and null preservation

**Area:** Masking
**Priority:** P0
**Lane:** Reference engine value assertions
**Execution status:** COMPLETE - 7/7 PASS

## Objective

Prove that a standalone FULL_NAME rule masks the source value deterministically without depending on
unrelated or incomplete row context, while null and empty source values remain unchanged.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-006-01 | A standalone full name masks deterministically and does not pass through the source | PASS |
| MASK-006-02 | Unrelated masked row values do not change the independent result | PASS |
| MASK-006-03 | First-name-only context does not create a mixed independent/derived value | PASS - DEF-0039 fixed |
| MASK-006-04 | Last-name-only context does not create a mixed independent/derived value | PASS - DEF-0039 fixed |
| MASK-006-05 | Null and empty full-name values remain null and empty even when both siblings exist | PASS |
| MASK-006-06 | Independent formatting and output-case controls remain effective | PASS |
| MASK-006-07 | Distinct reference names remain distinct and the full backend regression stays green | PASS - 547 tests, 0 failures, 0 errors |

## Result

- Forty-nine focused name, mode-boundary, and ordering tests passed.
- Complete backend regression passed 547 tests with zero failures and zero errors; six unrelated
  environment-conditional tests remained skipped.
- DEF-0039 was reproduced, fixed, and retested.
- No HARD-PASS was required.

## Execution Rules

- Use synthetic names only.
- Compare exact deterministic values.
- Derived mode activates only when both masked name components are available.
- Preserve null, empty, formatting, and case contracts.
