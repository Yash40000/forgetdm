# DSRC-003 - Type-or-Browse Metadata Validation

**Priority:** P0

**Lane:** Each connector
**Execution status:** NOT RUN

## Objective

Prove that typed and browsed source, schema, table, and column selections resolve to the same physical objects and reject mistakes before a long-running job starts.

## Preconditions

- Fixtures with two schemas, same table name in both, mixed-case/quoted identifiers, reserved words, Unicode identifiers where supported, views, and at least 4,000 catalog tables in the scale lane.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| DSRC-003-01 | Browse | Browse `/schemas`, `/tables?schema=`, `/columns`, and `/fks`. | Correct catalog hierarchy, types, PK/FK metadata, deterministic ordering, and no inaccessible system objects. |
| DSRC-003-02 | Typed | Type a valid alias, schema, table, and column using exact names. | Backend resolves the same IDs/metadata as browse and persists a canonical reference. |
| DSRC-003-03 | Negative | Type unknown alias/schema/table/column and near-miss spelling. | Save/preview/launch is blocked with a field-specific error; no runtime worker starts. |
| DSRC-003-04 | Qualification | Resolve `alias,schema.table` where the table differs from common defaults. | Correct data source and schema are selected; ambiguous unqualified names are rejected or explicitly resolved. |
| DSRC-003-05 | Identifiers | Browse/type mixed-case, quoted, reserved-word, space-containing, and Unicode names. | Names round-trip without unwanted case folding, truncation, or unsafe quoting. |
| DSRC-003-06 | Scale | Search and select from a 4,000-table schema. | UI remains responsive, results are paged/virtualized, and no implicit select-all occurs. |
| DSRC-003-07 | Drift | Drop/rename a selected object after design save, then preview. | Preflight detects drift and identifies the missing object before execution. |
| DSRC-003-08 | Permission | Browse with metadata-only, table-only, and denied accounts. | Empty schema is distinguished from insufficient privilege; unauthorized metadata is not leaked. |
| DSRC-003-09 | Injection | Type SQL fragments and delimiter/control characters into identifier fields. | Input is treated as an identifier or rejected; no SQL execution, log injection, or stack trace occurs. |

## Automation and Exit

- Implement the cases as a reusable connector metadata contract and UI type-or-browse suite.
- Pass requires typed and browsed selections to produce identical validated canonical references.
