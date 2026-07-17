# DISC-007 - Idempotent Discovery Rescan

**Priority:** P0

**Lane:** Reference connector
**Execution status:** NOT RUN

## Objective

Prove that rescanning refreshes machine suggestions in place, preserves analyst decisions, removes only stale in-scope suggestions, and never violates the physical-column uniqueness constraint.

## Preconditions

- Reference schema with deterministic PII/non-PII fixtures and unique key `(data_source_id, schema_name, table_name, column_name)`.
- At least one SUGGESTED, APPROVED, REJECTED, manual, stale, and out-of-scope classification.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| DISC-007-01 | Repeat | Run the same scan twice without changing data. | Both complete; classification count and physical-column keys are stable, and no `uq_class` duplicate error occurs. |
| DISC-007-02 | Refresh | Change sampled data/confidence for a SUGGESTED column and rescan. | Existing row is updated in place with current type/confidence/sample/time rather than duplicated. |
| DISC-007-03 | Approved | Approve a finding, change source data, and rescan. | APPROVED status, analyst edits, and selected masking recommendation remain unchanged. |
| DISC-007-04 | Rejected/manual | Reject one finding and add one manual classification, then rescan. | Both human decisions remain and no second classification is inserted for either physical column. |
| DISC-007-05 | Stale | Remove evidence for an in-scope machine suggestion and rescan. | Only the stale SUGGESTED row is removed; human-reviewed rows remain. |
| DISC-007-06 | Narrow types | Rescan with a PII type scope that excludes an existing column. | Excluded classifications are preserved/locked and are absent from scoped results as defined; no replacement type collides with them. |
| DISC-007-07 | Narrow tables | Rescan one focused table. | Other tables' classifications remain untouched; scoped findings and progress contain only the selected table. |
| DISC-007-08 | Concurrent | Start two scans of the same physical scope concurrently. | System serializes/rejects safely or uses conflict-safe upsert; no duplicate, partial transaction, or lost analyst decision occurs. |
| DISC-007-09 | Rollback | Inject a failure halfway through a rescan. | Transaction behavior is documented and consistent; no mixed duplicate/corrupt state remains and rerun succeeds. |
| DISC-007-10 | Results | Compare `/results`, findings UI, counts, and downloadable report after rescan. | All surfaces agree on current scoped classifications and reviewed statuses. |

## Automation and Exit

- Add PostgreSQL integration tests around `DiscoveryService.scan`, including concurrent and rollback lanes.
- Pass requires repeated scans without `uq_class` failures and zero loss of approved/rejected/manual decisions.
