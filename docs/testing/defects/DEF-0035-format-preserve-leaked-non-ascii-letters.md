# DEF-0035 - Format-preserving masking leaked non-ASCII letters

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED - fixed and retested 2026-07-22 |
| Found by story | MASK-002 (03, 04) |
| Component | Core format-preserving masking engine |

## Problem

`FORMAT_PRESERVE` replaced ASCII letters and digits but copied other Unicode letters unchanged.
Accented Latin, Greek, Cyrillic, Devanagari, CJK, and supplementary-plane identifiers could
therefore retain sensitive characters even though the masking operation reported success.

## Resolution

The formatter now walks Unicode code points, masks letters and digits deterministically within
their Unicode script, character category, and UTF-16 width, and preserves punctuation and symbols.
ASCII rotations also avoid returning the original character. Surrogate pairs are never split.

## Retest

- Representative multilingual and supplementary-plane input retained valid shape and changed every
  maskable code point for which the script contains an alternative.
- A large mixed-Unicode value remained deterministic with stable code-point and UTF-16 lengths.
- `MaskingEngineTest`: 39 passed, 0 failed.
