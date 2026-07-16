# ForgeTDM Master Test Plan

Document owner: Product Engineering and Quality Engineering  
Applies to: ForgeTDM 1.0.0, Java 17, Spring Boot 3.3.5, Next.js 16  
Baseline date: 2026-07-16  
Companion catalog: `FORGETDM_TEST_CASE_CATALOG.csv`

## 1. Purpose

This plan defines the evidence required to release ForgeTDM as an enterprise test data management
platform. It covers functional correctness, cross-database behavior, data privacy, referential
integrity, security, governance, scale, resilience, accessibility, and operations. Passing the Java
unit suite or demonstrating a clean PostgreSQL flow is necessary, but it is not sufficient for a
production claim.

The plan deliberately separates these evidence levels:

1. **Implemented**: code and automated component tests exist.
2. **Lab validated**: a repeatable end-to-end pack passed on a recorded real environment.
3. **Production certified**: functional, security, scale, recovery, upgrade, and support gates passed.
4. **Customer qualified**: a certified lane also passed the customer's representative schemas,
   infrastructure, volumes, controls, and acceptance criteria.

No capability or connector may be presented at a higher level than its retained evidence supports.

## 2. Quality objectives

ForgeTDM must:

- Never expose source secrets or clear PII in logs, reports, downloads, URLs, browser storage, or errors.
- Preserve declared database and tool-level keys, relationships, business rules, and cross-system identities.
- Produce deterministic data when a seed or deterministic masking policy is selected.
- Prevent accidental destructive target actions and make every approved destructive action auditable.
- Stream large work without retaining the complete data set in heap.
- Report truthful table, partition, row, stage, reject, retry, cancel, and completion state.
- Resume or fail safely after process, network, database, or native-loader interruption.
- Enforce RBAC, group scope, maker-checker, and admin behavior consistently in both UI and API.
- Behave consistently through the UI, REST API, exported runner, scheduler, and self-service request path.
- Remain operable with representative banking schemas, legacy identifiers, LOBs, encodings, and circular RI.

## 3. Scope inventory

### Product workspaces

- Authentication, access control, users, groups, roles, API tokens, and session expiry.
- Dashboard, audit, validation, reservation, and operational status.
- Data sources, diagnostics, schema browsing, connector inventory, and native-loader readiness.
- PII discovery, scan profiles, custom patterns, findings, impact map, policy generation, and exports.
- Masking Studio, masking scripts, lookup catalogs, policies, rule preview, and in-place masking.
- DataScope blueprints, table/column maps, subset relationships, previews, saved jobs, and provisioning.
- Synthetic generator catalog, reference lists, profiling, constraints, partitions, multi-target output,
  saved jobs, run history, cancellation, retry, and generated files.
- Mapping Designer, source/target/file assets, transformations, joins, staging, validation, preview,
  versions, execution, cancellation, and downloads.
- Business entities, identity crosswalk, freshness, reservations, snapshots, Micro-DB capsules,
  multi-application delivery, visual flows, governance, packages, versions, and promotions.
- Virtualization, virtual databases, refresh, rollback, schema fidelity, and time flow.
- Copybook Studio, mainframe files, Zowe connectivity, fixed/variable records, EBCDIC, generation,
  masking, transfer, download, and shared-key integrity with databases.
- Unstructured masking for text, CSV, JSON, XML, HTML, PDF, DOCX, and supported extracted formats.
- Self-service catalog, questionnaires, approvals, fulfillment, comments, cancellation, scheduling,
  exported runners, webhooks, and external automation.
- Private story planning, Forge Data Store grounding, plan approval, execution, feedback, and evidence.

### Supported and experimental connector lanes

The authoritative status and exact versions remain in
`docs/enterprise/CONNECTOR_CERTIFICATION_MATRIX.md`. This plan covers:

- Bundled lanes: PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, Db2 LUW/UDB, Db2 for z/OS, and H2 for tests.
- Optional/experimental lanes: Snowflake, Redshift, BigQuery, Teradata, SAP HANA, and SAP/Sybase ASE.
- File/mainframe lanes: local managed files, CSV/JSON/XML/HTML/document extraction, copybook files, and Zowe.

An optional JDBC connection is not vendor certification. Every vendor, server version, driver, OS,
direction, authentication method, encoding, and native loader is a separate test lane.

## 4. Out of scope for a release claim

- Shared-schema multi-tenant SaaS isolation until a dedicated architecture and penetration pack exists.
- A database/version/driver/native client combination not listed in signed certification evidence.
- Production use of H2 as the ForgeTDM control database.
- Performance extrapolation from a small demo; a 5 TB claim requires the scale gate in section 13.
- External organizational maturity such as 24x7 support, contractual SLA, analyst recognition, or partner
  coverage without the corresponding operating organization and signed process evidence.

## 5. Test governance

### Roles

| Role | Responsibility |
| --- | --- |
| Engineering | Unit/component tests, defect correction, migration compatibility, diagnostic tooling |
| Quality Engineering | Independent execution, regression ownership, traceability, evidence integrity |
| Security | Threat model, SAST/SCA/DAST, secrets, RBAC, penetration and privacy review |
| Database specialist | Vendor lane setup, data types, SQL semantics, native loaders, recovery |
| Mainframe specialist | Copybook, code page, record format, Zowe, RACF and transfer evidence |
| Performance engineer | Baseline, load, stress, endurance, 5 TB, capacity model |
| Product owner | Requirement acceptance, usability and supported-scope approval |
| Support/Operations | Install, upgrade, backup/restore, monitoring, runbook and ownership acceptance |

### Defect severity

| Severity | Definition | Release treatment |
| --- | --- | --- |
| S0 | Security/privacy breach, data corruption, uncontrolled destructive operation | Stop all affected testing; no release |
| S1 | Core flow unavailable, RI broken, wrong masking, unrecoverable job, cross-user access | No release |
| S2 | Material feature defect with a bounded workaround | Release only with signed exception |
| S3 | Minor functional or usability defect | May release with owner and target date |
| S4 | Cosmetic/documentation issue | Backlog unless it obscures risk or operation |

## 6. Environments and configuration matrix

### Required environments

1. **Unit**: isolated Java tests and frontend static/type checks.
2. **Component**: PostgreSQL control DB plus mocked or containerized dependencies.
3. **Integration**: real source and target engines, TLS, least-privilege users, and native clients.
4. **System**: production-like frontend/backend, reverse proxy, scheduler, monitoring, file vault, and backups.
5. **Performance**: isolated hosts and storage with repeatable telemetry and no unrelated workloads.
6. **Disaster recovery**: separate restore target and documented loss/recovery objectives.
7. **Customer qualification**: sanitized but structurally representative customer schemas and infrastructure.

### Platform matrix

- Backend: supported Windows and Linux JDK 17 distributions.
- Frontend: latest two stable versions of Edge, Chrome, Firefox, and Safari where supported.
- Viewports: 1366x768, 1920x1080, 2560x1440, tablet 768x1024, mobile 390x844 for supported pages.
- Themes: light, dark, every retained legacy theme, and Soccer Pro.
- Network: normal LAN, 100 ms latency, 1% loss, intermittent disconnect, proxy, and TLS inspection where approved.
- Locale/time: UTC, America/New_York DST boundaries, non-US locale, Unicode input, and server/browser time skew.

### Representative schema pack

Every relational connector lane must include:

- Single and composite PK/FK; tool-defined keys and relationships; unique and CHECK constraints.
- Parent/child/grandchild, self-reference, circular RI, multiple candidate relationships, and cross-schema links.
- No-PK tables, duplicate natural keys, nullable keys, identities/sequences, generated/default columns, and views.
- Reserved words, spaces, quoted mixed-case names, long identifiers, same-name objects across schemas, and 4,000 tables.
- Integer boundaries, high precision decimals, floats, booleans, date/time/time-zone, UUID/GUID, JSON/XML,
  binary, BLOB/CLOB/NCLOB/MAX, Unicode, empty string, null, malformed values, and vendor-specific types.
- Partitioned/sharded tables, skewed keys, sparse columns, wide rows, very large rows, and large object streaming.
- Banking data: customer, party, account, card, payment, loan, policy, claim, transaction, audit, and consent.

## 7. Test levels and execution cadence

| Level | Cadence | Gate |
| --- | --- | --- |
| Unit and component | Every pull request | 100% pass; no new critical static findings |
| API contract and migration | Every pull request | Backward-compatible or versioned; clean empty and upgrade DB |
| Frontend build/type/lint | Every pull request | Production build and TypeScript pass |
| PostgreSQL smoke | Every merge to main | Core discover-mask-generate-subset-provision flow passes |
| Full regression | Nightly | All automated functional suites pass |
| Real connector pack | Weekly and before release | Required lane evidence passes |
| Security and dependency | Daily/weekly plus release | No unresolved critical/high issue without exception |
| Performance baseline | Weekly | No unexplained regression over agreed threshold |
| Scale, endurance, recovery | Release candidate | Signed acceptance against section 13 |
| Customer qualification | Before customer deployment | Customer-specific pack and evidence pass |

## 8. Functional coverage requirements

The companion test catalog contains executable scenario families. Every family must include:

- Happy path.
- Boundary values, null/empty, Unicode, maximum length, and large volume.
- Invalid input, unavailable dependency, timeout, permission denial, duplicate request, and stale state.
- UI and direct API behavior.
- Refresh/remount/session-expiry behavior without losing unsaved work.
- Audit event, actor, timestamp, correlation ID, object/version, and sanitized error evidence.
- Concurrent users/jobs and idempotent retry where applicable.

### Authentication, RBAC, and administration

Verify login/logout/session timeout, disabled users, password controls, role/group inheritance, least privilege,
object scope, maker-checker separation, admin approval bypass where configured, API tokens, token revocation,
401 redirect, CSRF/session fixation, concurrent sessions, and audit visibility. Test every write endpoint with
authorized, unauthorized, unauthenticated, cross-group, and owner/non-owner identities.

### Data sources and connectors

Verify create/edit/delete, engine chooser, official lightweight icons, source/target role, test feedback,
diagnostics, browse/type validation, schema/table/column metadata, TLS, invalid certificates, secret redaction,
timeouts, pooling, reconnect, locked H2 deletion, system-schema exclusion, case handling, and native-loader
readiness/fallback. Run the full data-type and schema pack in both source and target directions.

### PII discovery and policy creation

Verify generic, PCI, HIPAA, and custom profiles; selectable PII scope; table scope; zero-table rejection;
metadata and sampled-value detection; custom patterns; scan progress; cancel/retry; duplicate-safe rescans;
findings table/column filters; approve/reject/manual classification; impact map; report download; recent scans;
policy creation; policy name rules; and no out-of-scope PII types in results. Measure false positive/negative
rates against a labeled corpus, including obfuscated values and multilingual text.

### Masking

Verify every masking function and script with null, empty, Unicode, maximum length, invalid parameters,
deterministic seed, format preservation, uniqueness, collision handling, row context, and data-type fit.
Include direct lookup, hash lookup, first/last/full name composition, all payment card brands with valid Luhn
and guaranteed uniqueness within the requested run, addresses, dates, national IDs, account/routing values,
emails, phones, and free text. Verify policy CRUD/versioning, preview, per-column override, no-mask choice,
cross-table consistency, cross-database consistency, in-place mode, append/update mode, LOBs, and no clear PII
in rejects or logs.

### DataScope, subset, and provisioning

Verify blueprint name rules, source/target browse or typed validation, profile opt-in, duplicate target prevention,
one table row per map, automatic and manual column maps, literals, unused columns, policy bulk action, conditional
filters, row limits, and source overrides using alias/schema/table syntax. Verify driver selection, Q1 parent pull,
Q2 child cascade, independent tables, per-edge relationship selection, DB versus tool-defined relationships,
none selection, traversal explanation, circular closure, dry-run counts, guardrails, saved jobs, schedules,
admin/non-admin approval, cancellation, update without delete/truncate, all prep/load modes, SQL Server parameter
limits, Db2 syntax, native loader/fallback, partial failure, restart, and row/orphan validation.

### Synthetic data generation

Execute the existing 100-scenario pack plus generator, constraint, seed parity, partition, banking, and
multi-system suites. Verify generator catalog/try, reference lists, live-column import, source profiling,
manual tables, tool-defined keys/relationships, CHECK and business rules, composite/circular RI, null rates,
correlated columns, full-name consistency, all card brands, addresses by US state, date/time, numeric precision,
LOBs, files, mainframe shared keys, output targets, append/update/insert/upsert/replace/truncate-only behavior,
native loading, progress, football run state, table/partition details, retry/cancel, job naming, save/load/delete,
shell export, maker-checker, seed replay, and memory-bounded streaming through at least the 5 TB qualification.

### Mapping Designer

Verify full-screen operation, source/target panels, table/file assets, CSV and structured files, draggable and
resizable compact nodes, persistent input/output port links, link selection/deletion, join semantics, source
qualifier, expression, filter, router, lookup, aggregate, sorter, union, sequence, staging, target, reusable
functions, type propagation, rename/cast, location-aware insertion, auto-layout, zoom/pan, undo/redo, save/version/
restore, strict compile validation, SQL plan, preview, multi-source/federated execution, cancellation, rejects,
download, and recovery of unsaved work.

### Business entities and Micro-DB

Verify entity/model CRUD, multiple blueprints/applications, member selection/editing, root/business key,
tool and DB relationships, identity crosswalk across engines, ambiguous identity handling, freshness policies,
watermarks, snapshots, reservations, capsules, encryption/isolation, multi-application fan-out, flow builder,
debugger, loops/branches/errors/compensation, two-phase behavior where claimed, packages, immutable versions,
promotion DEV-to-QA/UAT, rollback, catalog/evidence, maker-checker, lineage retention, deletion confirmation,
and full-screen/collapsible workspaces. Prove one entity instance remains consistent across at least three engines.

### Virtualization

Verify virtual DB create/delete, physical schema creation and selection, non-public schema fidelity (including
Db2 UDB), provision/refresh/rollback, time flow, isolation, connection lifecycle, stale handles, failure cleanup,
concurrent clients, metadata visibility, query correctness, and deletion without locked-file residue.

### Mainframe and copybook

Verify copybook create/edit/delete/list refresh, parser errors, REDEFINES, OCCURS, COMP/COMP-3, signed decimals,
fixed and variable records, RDW, multiple EBCDIC code pages, Unicode conversion, null/default semantics, field
masking, deterministic generation, CRF/shared-key integrity with database tables, large streaming files,
download checksum, Zowe TLS chain/hostname/trust store, invalid certificate feedback, RACF denial, dataset list/
stat, upload/download interruption, restart, and zero clear PII in logs or temporary files.

### Unstructured data

Verify preview and governed jobs for plain text, CSV, JSON, XML, HTML, PDF, DOCX, logs, and every advertised
format. Include prose without labels, split tokens, punctuation, OCR/extraction limitations, nested documents,
malformed files, password-protected/encrypted files, zip bombs, oversized files, MIME/extension mismatch,
macros/active content, path traversal, cancellation, progress beyond 5%, output fidelity, detection counts,
download, retention deletion, and secure temporary-file cleanup.

### Self-service, automation, and private planning

Verify product publish/version/enable/disable, questionnaire validation, environment/volume/reservation guards,
request/approve/reject/comment/cancel/fulfill states, requester cannot approve self, admin behavior, schedules,
runner export, token safety, webhooks with signing/retry/deduplication/dead-letter handling, Jenkins/Azure DevOps/
ServiceNow/Jira integration contracts, audit lineage, private story parsing, grounded catalog retrieval, unsupported
request refusal, plan explanation, approval gates, execution, cancellation, feedback, and deletion of catalog items.

## 9. API, persistence, and migration testing

- Generate an OpenAPI contract snapshot and detect incompatible changes.
- Validate request schemas, field lengths, enums, content types, pagination, sorting, filtering, and bounded errors.
- Verify all create/update/delete operations for idempotency or explicit duplicate behavior.
- Test optimistic concurrency and stale updates for editable artifacts.
- Run every Flyway migration on an empty DB, previous release DB, production-like volume, and rollback rehearsal.
- Restart during migration and verify safe recovery or an actionable stop state.
- Verify PostgreSQL control-DB backup/restore, sequence continuity, foreign keys, indexes, and retention jobs.
- Test transaction boundaries and no partial metadata state after a failed source/target operation.

## 10. Security and privacy plan

### Required analyses

- Threat model for UI, API, control DB, source/target DBs, native clients, file vault, Zowe, webhooks, and runners.
- SAST, dependency/SBOM, license, secret, container/image, infrastructure, and DAST scans.
- Manual penetration testing for OWASP Top 10, API authorization, SSRF through JDBC/webhooks, injection,
  unsafe deserialization, file upload, path traversal, command injection, XSS, CSRF, and privilege escalation.
- TLS and certificate validation, secure cookies/headers, rate limits, account lockout, and error sanitization.
- Encryption in transit and at rest, secret rotation, key loss/recovery, backup protection, and temporary files.
- Data retention, purge, legal hold, audit immutability, lineage, export authorization, and right-to-delete behavior.

### Security release gate

No open critical issue. No open high issue without Security and Product sign-off, compensating control,
customer disclosure where relevant, owner, and expiration date. No secret or clear production PII in the repo,
artifact, test evidence, logs, telemetry, or generated support bundle.

## 11. Reliability, recovery, and concurrency

Inject failures during discovery, masking, generation, native load, JDBC commit, file transfer, package promotion,
and multi-application delivery:

- Backend termination, host reboot, database restart/failover, network partition, timeout, disk full, read-only
  disk, permission change, native-client crash, malformed row, deadlock, lock timeout, and control-DB outage.
- Verify truthful terminal state, bounded retries with backoff, no duplicate committed data, resumability where
  supported, explicit cleanup, retained evidence, and operator guidance.
- Run concurrent jobs against the same and different targets; verify leases, cancellation ownership, target
  collision controls, reservation conflict, rate limits, pool bounds, and no cross-user event leakage.
- Execute 24-hour and 72-hour endurance runs; track heap, threads, file handles, pools, temp storage, metadata
  growth, browser memory, and progress polling load.

## 12. Performance and capacity plan

Establish baselines for discovery columns/second, masked rows/second, generated rows/second, copied bytes/second,
subset traversal time, file throughput, UI response, API latency, control-DB load, CPU, heap, GC pause, network,
and storage IOPS. Record p50, p95, p99, maximum, error rate, and resource saturation.

Required workloads:

| Workload | Purpose |
| --- | --- |
| 100 rows / 2 tables | Pull-request smoke and exact-value validation |
| 100,000 rows / 20 tables | Daily representative banking regression |
| 10 million rows / 100 tables | Streaming, partition, progress, retry, and memory baseline |
| 100 million rows / 500 tables | Concurrency, skew, metadata, and operational usability |
| 1 TB | Pre-scale infrastructure and throughput validation |
| 5 TB | Production-scale qualification, not an extrapolation |

Every performance result must identify commit/image, hardware, topology, server/driver/client versions, schema,
row width, LOB mix, worker/partition/batch settings, prep/load mode, loader strategy, and validation queries.

## 13. Five-terabyte qualification gate

A claim that ForgeTDM handles 5 TB is allowed only after all of the following pass:

1. Inventory exact source and target bytes, rows, tables, partitions, LOB bytes, largest table, and key skew.
2. Run a read-only discovery/profile pass and retain metadata, duration, and resource telemetry.
3. Run a 1% pilot, then 10%, 1 TB, and finally 5 TB using identical code and recorded tuning.
4. Exercise JDBC fallback and the intended native loader separately where both are claimed.
5. Keep steady-state heap below the agreed limit with no data-volume-proportional growth.
6. Cancel an active run and prove bounded stop time, committed-row accounting, and safe rerun.
7. Interrupt the backend, one source, one target, and one native client; prove recovery and no duplicate data.
8. Validate source/target counts, PK/unique constraints, FK orphan count, CHECK rules, null rates, distributions,
   deterministic samples, masking leakage, checksums where applicable, and reject reconciliation.
9. Run at least two concurrent representative jobs and one 24-hour endurance window.
10. Verify UI/API progress remains timely and accurate without unbounded run-history or partition payloads.
11. Retain sanitized logs, metrics, query plans, loader logs, validation reports, and signed results.
12. Capacity owner signs the measured throughput and resource envelope; Product signs the supported topology.

## 14. UX, accessibility, and visual regression

- Keyboard-only operation, visible focus, logical tab order, shortcuts, escape/close behavior, and focus return.
- WCAG 2.2 AA contrast, labels, accessible names for icon buttons, status not conveyed by color alone, screen reader
  announcements for progress/errors, reduced motion, zoom to 200%, and responsive layout.
- No clipping, overlap, horizontal scrolling in standard full-screen workspaces, layout shift, or hidden actions.
- Browse/type patterns, modal/drawer/full-screen ownership, dirty-state prompts, save/discard/save-and-close, and
  session-expiry recovery are consistent.
- Visual snapshots for all 21 routes, themes, empty/loading/error/success states, drawers, dialogs, and the soccer
  run animation at kickoff, active stages, failure, cancellation, goal, and completion celebration.
- Measure initial load, route change, search/filter, large table virtualization, and progress update responsiveness.

## 15. Compatibility, install, upgrade, backup, and DR

- Clean install with documented environment variables and no demo profile.
- Upgrade from every supported release; preserve users, policies, jobs, evidence, packages, and encrypted secrets.
- Downgrade refusal must be clear and must not mutate data.
- Offline/air-gapped dependency and native-client installation where supported.
- Reverse proxy, custom base URL, TLS termination, corporate CA, proxy, and firewall validation.
- Backup during idle and active periods; restore to a separate host; verify credentials, jobs, audit chain, and files.
- Execute HA/DR runbook, measure RPO/RTO, fail back, and reconcile jobs that were running at failure.
- Validate observability: health, readiness, Prometheus metrics, structured sanitized logs, correlation IDs, alerts,
  capacity thresholds, and support bundle.

## 16. Evidence and traceability

Every executed case records:

- Test ID, requirement/risk, ForgeTDM commit and build, environment and connector lane.
- Preconditions, sanitized input fixture, exact steps or automation version, and expected result.
- Actual result, start/end time, actor, run/job IDs, row/byte counts, and loader strategy.
- Screenshots only when useful; prefer machine-readable JSON, SQL assertions, metrics, and checksums.
- Defect link, rerun evidence, reviewer, date, and PASS/FAIL/BLOCKED/NOT APPLICABLE status.

Evidence must never contain passwords, tokens, connection secrets, customer data, or unmasked PII. Connector
evidence uses `docs/enterprise/connector-certification-evidence/TEMPLATE.md`.

## 17. Entry and exit criteria

### Entry

- Requirements and supported scope are versioned.
- Release candidate build is immutable and deployable.
- Migrations, fixtures, accounts, certificates, drivers, native clients, storage, and monitoring are ready.
- Test data is synthetic or approved/sanitized, with expected outcomes defined.
- Known defects and exceptions are reviewed before execution.

### Release exit

- All mandatory catalog cases pass for the claimed scope.
- Unit/component, API contract, frontend build, migration, PostgreSQL smoke, and full regression are green.
- Every claimed connector lane has signed evidence for its exact configuration.
- Zero open S0/S1 defects; S2 exceptions meet the governance rule.
- Security, scale, endurance, cancellation, recovery, backup/restore, and upgrade gates pass.
- Counts, RI, masking leakage, rejects, audit, and lineage reconcile.
- Runbooks, limits, compatibility matrix, release notes, and operator guidance match observed behavior.
- Product, QE, Security, Operations, and connector owners sign the release record.

## 18. Initial execution order

1. Freeze requirements and map the companion catalog to current automated tests.
2. Make Java tests, frontend build/type/lint, OpenAPI compatibility, migrations, and PostgreSQL smoke mandatory CI.
3. Complete PostgreSQL 16/17 reference certification with TLS, LOB, streaming, cancellation, and recovery.
4. Certify the first banking lane: Db2 LUW 11.5 with representative composite/circular RI and code page.
5. Validate Oracle 19c and SQL Server 2022, including CLOB/MAX, parameter limits, and native loader fallback.
6. Validate MySQL 8.4 and MariaDB 11.4 independently.
7. Run full mainframe copybook/Zowe acceptance with real TLS/RACF and large EBCDIC files.
8. Complete security, accessibility, upgrade, backup/restore, and 24-hour endurance gates.
9. Execute staged 1%/10%/1 TB/5 TB qualification on the intended production topology.
10. Repeat the appropriate customer qualification pack before each banking deployment.

