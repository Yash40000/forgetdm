# DEF-0037 - Key-consistency salt depended on relationship iteration order

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED - fixed and retested 2026-07-22 |
| Found by story | MASK-004 (03, 04) |
| Component | Provisioning FK-consistency salt graph |

## Problem

The union-find graph used the last visited relationship endpoint as its masking-salt representative.
For a branching relationship graph, reversing metadata or table iteration order changed the shared
`ri:` salt. The same source key could therefore receive a different masked value even though the
relationships and masking policy were unchanged.

## Resolution

Union operations now select the lexicographically smallest connected-component representative.
The representative is independent of metadata order, while every parent/child member still shares
one salt and separate composite-key components remain isolated.

## Retest

- The original branching graph failed before the fix and passed after relationship order was reversed.
- Eight focused cross-table consistency checks passed.
- PostgreSQL, Oracle, and MySQL each retained 48/48 parent-child joins with the same checksum.
- Masking/provisioning compatibility regression passed 125/125.
