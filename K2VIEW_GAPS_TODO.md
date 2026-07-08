# K2View-Style Capability Gaps TODO

Captured as the follow-up list after adding the first Business Entity Model slice.

## Done

- [x] Business Entity Model: add a catalog/API/UI for defining customer/account/policy-style business entities and mapping their member tables across systems. First slice supports blank creation, creation from DataScope, member table editing, and audit entries.
- [x] Snapshot, rollback, and time travel: add entity-scoped snapshot headers, member evidence, optional physical Virtualization snapshots, rollback preview, explicit rollback confirmation, and audit logging.
- [x] Reservation management by business entity: reserve whole business objects with per-member key evidence, conflict checks, TTL, release/expiry lifecycle, and audit logging.
- [x] Production issue recreation: capture issue context, linked snapshot/reservation, business keys, privacy action, replay instructions, and a package manifest.
- [x] AI-assisted synthetic/look-alike data: create metadata-only generator plans with no raw source values stored, deterministic seed policy, and safety evidence.
- [x] Enterprise catalog depth: sync searchable entity/member catalog assets with ownership, domain, tags, certification, lineage, dependencies, and quality scores.
- [x] Governance workflow depth: add maker-checker approval requests, risk evidence, signed decisions, e-signature hash, and audit entries.
- [x] Cross-system subsetting by entity: create entity execution plans across member systems with source/target environment, operation type, validation evidence, and approval linkage.
- [x] Scale and native loading: add per-engine loader strategy evidence for Postgres COPY, Oracle SQL*Loader/direct path, SQL Server bulk copy, DB2 LOAD, Snowflake stage copy, MySQL LOAD DATA, and JDBC fallback.
- [x] Operational packaging: add scheduler-ready operational packages with manifests, health-check contract, promotion evidence, and downloadable PowerShell runner script.
- [x] Wire approved Business Entity execution plans directly into DataScope/Synthetic run engines, with execution-run evidence.
- [x] Implement physical native-loader executors for planned loader strategies: Oracle SQL*Loader/direct path, SQL Server bcp, DB2 LOAD, Snowflake stage COPY, MySQL LOAD DATA, plus Postgres COPY and JDBC fallback. Configured native clients are invoked for synthetic fast-load paths; unavailable clients fall back cleanly.
- [x] Add operational package versioning and promotion between environments with immutable artifact hashes, retention metadata, promotion evidence, and audit entries.

## Future Hardening

- [ ] Extend DataScope masked-row loads to stage already-masked files and invoke the same native-loader executors where the target database/client supports safe bulk ingest.
- [ ] Add native-loader integration tests against real Oracle, SQL Server, DB2, Snowflake, and MySQL containers/environments in CI.
- [ ] Add promotion approval dashboards and retention-expiry review workflows for package artifacts.
