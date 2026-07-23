# DEF-0039 - Partial name context mixed masking modes

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED - fixed and retested 2026-07-23 |
| Found by story | MASK-006 (03, 04) |
| Component | FULL_NAME row-context mode selection |

## Problem

When only one masked sibling name was available, FULL_NAME used that masked value while independently
generating the missing component from the source full name. The result was neither a fully derived name
nor a fully independent mask and could create internally misleading person records.

## Resolution

Context-derived composition now activates only when both masked first-name and last-name values are
available. With incomplete or unrelated context, FULL_NAME masks all components independently from the
source value. The existing null and empty short-circuit remains unchanged.

## Retest

- First-only and last-only contexts now exactly match the independent baseline.
- Complete two-component context still satisfies MASK-005 derived composition.
- Null, empty, formatting, case, determinism, and distinct-input checks passed.
- Forty-nine focused tests and the 547-test backend regression passed with zero failures or errors.
