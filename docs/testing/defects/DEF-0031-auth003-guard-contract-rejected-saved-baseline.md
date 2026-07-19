# DEF-0031 - AUTH-003 guard contract rejected the saved-design baseline

| Field | Value |
|---|---|
| Severity | LOW |
| Status | CLOSED |
| Found by story | AUTH-003 regression |
| Verified | 2026-07-19 |

## Problem

The AUTH-003 source contract required one historical implementation spelling: `initialFingerprint.current`. The Synthetic designer now retains `savedFingerprint`, updates it after a successful save, and compares the current plan fingerprint with that saved baseline. This preserves the unsaved-change warning and improves it by making a saved design clean again, but the literal-string assertion falsely failed the full regression.

## Resolution

The contract now accepts either supported retained-baseline implementation while still requiring `useUnsavedGuard` to compare the current plan fingerprint against that baseline. It does not weaken the requirement to a mere import or comment check.

## Verification

`Auth003FrontendBehaviorTest` passed after the contract correction. The complete Maven regression then passed 70 suites and 291 tests with zero failures/errors; its one skip is the separately documented `SyntheticScenarioPackTest` external fixture. The Synthetic designer continues to call `useUnsavedGuard(fingerprint !== savedFingerprint)` and updates the baseline only on explicit save/reset paths.
