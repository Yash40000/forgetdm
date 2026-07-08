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

- [ ] Add a K2View-style logical Micro-Database / Entity Capsule layer: persist each business entity instance (for example Customer 360 / CUST-10025) as a governed reusable store with canonical identity, cross-system keys, table fragments, relationships, source watermarks, masking/synthetic versions, lineage, reservations, access grants, and provisioning history. Prefer shared physical tables such as `be_entity_instances`, `be_entity_fragments`, `be_entity_versions`, `be_entity_watermarks`, `be_entity_access_grants`, and `be_entity_lineage_events` rather than a real database per customer/account. DataScope, Synthetic, Freshness, Governance, and Provisioning should hydrate from and provision out of these capsules.
- [ ] Extend DataScope masked-row loads to stage already-masked files and invoke the same native-loader executors where the target database/client supports safe bulk ingest.
- [ ] Add native-loader integration tests against real Oracle, SQL Server, DB2, Snowflake, and MySQL containers/environments in CI.
- [ ] Add promotion approval dashboards and retention-expiry review workflows for package artifacts.
- [ ] Add DB2 UDB as an optional ForgeTDM control/backend database while keeping PostgreSQL as the default. Use a deployment profile/config choice per client, DB2 JDBC driver, DB2 Hibernate/Flyway setup, DB2-specific migrations, and a small backend SQL dialect layer for raw queries (`LIMIT` vs `FETCH FIRST`, identity columns, text/json columns, upsert syntax). One ForgeTDM instance should use one backend at a time.
