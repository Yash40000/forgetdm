# DEF-0038 - Derived full name ignored masked row context

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED - fixed and retested 2026-07-22 |
| Found by story | MASK-005 (02, 05, 06, 07) |
| Component | Masking engine and provisioning row evaluation |

## Problem

`FULL_NAME` parsed and masked its source text independently even when the row context already contained
masked first-name and last-name values. Physical column order could also evaluate a derived full name or
NAME_SAFE email before its sibling components. One keyed in-place path did not retain masked values in
the row context at all. The result could be an internally inconsistent person record.

## Resolution

- FULL_NAME now composes masked sibling name values when present and retains its independent fallback.
- Common first-name and last-name aliases are resolved in deterministic order.
- Copy, keyed in-place, and rebuild in-place execution evaluate name components before derived fields
  while preserving the target writer order.
- DataScope preview and Business Entity capsule masking use the same component-before-derived rule.
- The missing keyed in-place context write was restored.

## Retest

- Exact `FIRST LAST` and `LAST, FIRST` assertions passed.
- Alias, output-case, null, independent fallback, and reversed physical-order checks passed.
- Forty-three focused tests passed.
- Complete backend regression passed 541 tests with zero failures and zero errors.
