# DEF-0036 - Masked values bypassed target datatype-fit validation

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED - fixed and retested 2026-07-22 |
| Found by story | MASK-002 (05, 06) |
| Component | Provisioning copy and in-place masking paths |

## Problem

Provisioning knew only each target column's JDBC type, not its character length or numeric
precision and scale. Failed conversions fell back to the original string, allowing an invalid
masked or literal value to reach a JDBC batch and fail with an opaque vendor error.

## Resolution

Provisioning now reads target column type, size, and scale and applies one final target-fit guard
to copy and in-place values. Character values are clipped by Unicode code point, numeric precision
and scale are enforced without implicit rounding, scalar strings are converted strictly, and any
failure identifies the physical target column before the batch is written.

## Retest

- Nulls, Unicode-safe character fitting, unbounded CLOBs, integer/date/boolean conversion, invalid
  scalar rejection, and decimal precision/scale boundaries passed.
- Provisioning datatype-fit and LOB compatibility coverage: 14 passed, 0 failed.
