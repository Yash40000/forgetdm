# DEF-0034 - Masking functions silently accepted malformed parameters

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED - fixed and retested 2026-07-22 |
| Found by story | MASK-001 (03) |
| Component | Masking policy and preview preflight validation |

## Problem

Several functions treated unknown option text as a default. For example, `FIRST_NAME` accepted an
output case of `SIDEWAYS`; malformed address parts, card modes, split-field definitions, and regular
expressions could also reach execution without a clear configuration error.

## Resolution

`PolicyController.validateRuleConfig` now validates the complete constrained parameter surface:
choice modes, date shifts and formats, age bands, character-preservation ranges, token lengths,
lookup definitions, numeric bounds, redaction modes, row-dispatch maps, regular expressions,
split-field mappings, date-aging specs, identifier modes, and required fixed/script values.

## Retest

- All 43 built-ins executed with valid reference configurations.
- Every constrained function rejected its malformed reference configuration.
- 73 focused masking/policy tests passed with zero failures or errors.
- Edge rendered all 43 Masking Studio functions, completed a valid SSN preview, and displayed the
  expected validation error for malformed `NUMERIC_NOISE` input.
