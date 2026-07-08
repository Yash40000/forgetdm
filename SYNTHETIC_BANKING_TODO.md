# Synthetic Data Generation Banking TODO

Captured on 2026-06-21 after reviewing the current ForgeTDM synthetic data generation flow.

## Production Readiness

- [x] Add a Banking Safe Profile mode so source profiling never saves raw sensitive source values into saved job `plan_json`.
- [x] Classify profiled columns with PII/PCI/financial-data detection before choosing `WEIGHTED` or sampled-value generators.
- [x] Add allowlist rules for which columns may use real top-value distributions.
- [x] Add warnings when learned distributions may contain customer, account, card, tax ID, email, phone, address, or transaction descriptors.

## Banking Domain Realism

- [x] Create banking domain templates for customer, account, card, loan, payment, statement, merchant, branch, and KYC/risk entities.
- [ ] Add business-rule generation for account balances, limits, transaction dates, statement cycles, delinquency, chargebacks, fraud flags, and risk scores.
- [ ] Add cross-table consistency checks for customer-account-card-loan-payment relationships.
- [x] Add scenario pack fields for normal customer, dormant account, high-risk customer, delinquent loan, fraud flag, KYC review, and closed/hold account states.

## Large Data And Relationships

- [ ] Strengthen streaming mode for composite foreign keys.
- [ ] Strengthen streaming mode for cross-table `LOOKUP` derived values.
- [ ] Add tests for single-column FK, composite FK, existing-target FK pools, FK cardinality, and orphan validation.
- [ ] Add explicit UI guidance when a plan uses features that are weaker in streaming mode.
- [x] Add configurable synthetic worker count with `forgetdm.synthetic.workers`.

## Enterprise Job Operations

- [ ] Replace fixed local synthetic executor with configurable worker pool settings.
- [ ] Add queued, running, paused, cancelled, failed, completed states with admin controls.
- [ ] Add resumable jobs or checkpoint/restart behavior for very large DB loads.
- [ ] Add distributed worker or job lease support so long jobs are not tied to one JVM process.
- [ ] Add service-account/API-token auth for downloaded PS1/SH runners instead of username/password environment variables.

## Database And Platform Coverage

- [ ] Expand dialect support beyond Postgres for Oracle, DB2, SQL Server, Snowflake, and Teradata.
- [ ] Replace profile SQL that assumes `LIMIT` with dialect-aware sampling.
- [ ] Add fast-load strategies per platform, such as Oracle array/bulk load, SQL Server bulk copy, DB2 load, and Snowflake stage copy.
- [ ] Add schema/identifier handling tests for quoted names, mixed case, reserved words, and special characters.

## Security And Governance

- [x] Add URL allowlists and SSRF protection for the `API` generator.
- [x] Persist generation lineage with plan hash, saved-job context, approval snapshot, plan summary, and target constraint snapshot.
- [x] Add saved-job approval/sign-off lifecycle with request, approve, reject, and audit entries.
- [x] Capture DB `CHECK` constraints from target metadata and store them as run evidence.
- [x] Add timeout, retry, and concurrency throttle for API-backed generators.
- [x] Add strict maker-checker enforcement for saved jobs that write to controlled banking environments.
- [x] Add environment/tag-aware controlled target enforcement for direct DB generation.
- [x] Add granular permissions for synthetic profile, manage, run, direct-run, approve, cancel, and export actions.
- [x] Complete immutable audit entries for profile, run, cancel, approval, delete, and download-runner export.
- [ ] Add secret-manager integration for scheduler credentials.
- [x] Support bearer tokens for scheduler/API execution and generated runners via `FORGETDM_TOKEN`.

## Constraint-Driven Rules

- [x] Auto-enforce simple captured DB `CHECK` rules during synthetic generation (`IN`, `BETWEEN`, `>=`, `<=`, `>`, `<`).
- [ ] Parse and enforce richer row-level DB expressions such as `CASE`, `LIKE`, date windows, boolean combinations, and cross-column arithmetic.
- [ ] Surface unsupported captured constraints as actionable UI warnings with suggested generator changes.

## Testing And Validation

- [ ] Add integration tests for async synthetic generation jobs.
- [ ] Add integration tests for saved job create, load, update, delete, rerun, and shell runner download.
- [ ] Add Postgres integration tests for streaming insert, COPY fast load, truncate-only, delete-first, update, insert-update, commit checkpoints, and reject handling.
- [ ] Add cancellation tests that verify statement cancellation and persisted final state.
- [ ] Add performance baseline tests for 1M, 10M, and 40M row generation.
- [x] Add validation reports for planned rows, loaded rows, rejected rows, FK orphan counts, nullability, and unique-column duplicate evidence.

## UX Improvements

- [x] Show a banking-readiness warning when learned source distributions may be unsafe.
- [ ] Show estimated runtime and memory mode before running large jobs.
- [x] Show exactly which tables will use streaming, COPY, row-by-row insert, update, or upsert.
- [x] Show generated shell-runner usage in the downloaded PS1/SH, including bearer-token mode.
- [ ] Add saved-job versioning so users can compare and roll back job definitions.
