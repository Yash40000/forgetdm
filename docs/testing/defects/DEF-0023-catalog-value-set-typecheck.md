# DEF-0023 - Catalog value-set metadata typing broke the frontend type gate

| Field | Value |
|---|---|
| Severity | LOW |
| Status | **CLOSED - fixed and verified 2026-07-18** |
| Found by story | AUTH-003 exit gate |
| Component | Frontend data catalog mapping |

## Impact

`npm.cmd run typecheck` failed in `frontend/src/features/catalog/hooks.ts` because the conditional
value-set metadata expression was inferred as an optional `Values` property and could not satisfy
the required `Record<string, string>` contract. The authentication behavior tests passed, but the
story's frontend regression gate could not be accepted while TypeScript was red.

## Fix

The value-set mapper now constructs explicitly typed `Record<string, string>` metadata and only adds
the `Values` field when values exist.

## Verification

- `npm.cmd run typecheck`: passed (`next typegen` and `tsc --noEmit`, exit code 0).
- The change is isolated to the catalog mapper and does not alter authentication behavior.
