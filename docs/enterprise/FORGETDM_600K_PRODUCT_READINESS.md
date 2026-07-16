# ForgeTDM USD 600K Product Readiness

Document owner: Product, Engineering, Quality, and Commercial Readiness

Baseline date: 2026-07-16

## Objective

Build ForgeTDM into a technically credible, commercially useful enterprise TDM product that can support a
strategic value target near USD 600,000. This is an evidence target, not a promise that a buyer will pay that
amount. Technology, testing, customer proof, revenue, support, intellectual-property ownership, and buyer fit
must all be present.

ForgeTDM is not valued because it uses Java, Spring Boot, PostgreSQL, or Next.js. Those are implementation
choices. Value comes from the difficult system behavior already being assembled: safe cross-database data
movement, deterministic masking, referentially correct generation, legacy/mainframe support, governed
self-service, business entities, operational evidence, and the amount of customer risk and implementation time
the product removes.

## Honest starting position

### Present strengths

- Broad working capability across discovery, masking, DataScope, generation, mapping, business entities,
  mainframe, virtualization, unstructured files, automation, security administration, and self-service.
- Java regression coverage, a compiled Next.js frontend, acceptance fixtures, banking scenarios, connector
  diagnostics, governance controls, audit, and documented HA/DR and certification processes.
- A private source repository and a growing body of repeatable test evidence.

### Evidence still required

- No database lane is yet recorded as production certified in the connector certification matrix.
- The 212-case master catalog has been designed, but every mandatory case has not yet produced retained evidence.
- Independent penetration, accessibility, 24/72-hour endurance, upgrade, DR, and 5 TB qualification are pending.
- Paid pilots, recurring revenue, customer references, contractual support, and an operating support history are
  business evidence and cannot be created by code alone.

## Readiness scorecard

The score is evidence based. A partially implemented feature receives no credit for a certification or customer
gate. Target readiness is at least 90/100 with every mandatory gate passing.

| Track | Weight | Full-credit evidence | Mandatory |
| --- | ---: | --- | --- |
| Product correctness | 15 | All P0 functional cases pass; zero open S0/S1; bounded S2 exceptions | Yes |
| Security and privacy | 15 | Independent penetration test, SAST/SCA/DAST, privacy review, remediation | Yes |
| Connector certification | 15 | PostgreSQL plus two of Db2 LUW, Oracle, SQL Server lab validated | Yes |
| Scale and resilience | 10 | Measured target scale, cancellation, recovery, concurrency, endurance | Yes |
| Deployment and operations | 10 | Install, upgrade, backup/restore, HA/DR, observability and runbooks pass | Yes |
| UX and accessibility | 5 | Critical workflows pass browser, accessibility and usability acceptance | Yes |
| Customer proof | 10 | Two banking pilots pass; at least one independently referenceable outcome | Yes |
| Commercial proof | 10 | Repeatable pricing and paid demand; target USD 100K-150K ARR or equivalent | Yes |
| Support readiness | 5 | Named support, SLA policy, escalation, onboarding and incident process | Yes |
| IP and diligence | 5 | Ownership, licenses, SBOM, brand, privacy and clean evidence room | Yes |

### Automatic stop conditions

ForgeTDM is not USD 600K ready when any of these is true:

- A known clear-PII leak, cross-user authorization defect, data corruption path, or uncontrolled destructive action.
- A claimed connector lacks real-engine evidence for the advertised version and direction.
- Scale is extrapolated from a demo rather than measured.
- Customer or revenue claims cannot be independently verified.
- Source ownership, license compatibility, credentials, or customer-data rights are unclear.
- A production client would depend on one individual without a documented support and recovery path.

## Product bar

### Correctness

- Execute all 212 test families in `docs/testing/FORGETDM_TEST_CASE_CATALOG.csv`.
- Automate every stable P0 case and retain machine-readable results by immutable commit.
- Maintain zero S0/S1 defects and publish known limitations instead of hiding them.
- Add mutation or equivalent fault-sensitive testing to masking, relationships, generators, and authorization.

### Connector depth

- Certify PostgreSQL first as the regression reference.
- Certify Db2 LUW as the first banking legacy lane.
- Certify Oracle 19c and SQL Server 2022 next, including LOBs, partitions, native loaders, and fallback.
- Keep MySQL and MariaDB independent; do not inherit certification between them.
- Label optional JDBC engines experimental until a bounded feature contract and real evidence exist.
- Qualify Db2 z/OS and Zowe only in a real mainframe lab with TLS, RACF, code-page, restart, and transfer evidence.

### Banking trust

- Prove deterministic, irreversible masking with cross-table and cross-system consistency.
- Prove PK, FK, CHECK, uniqueness, business-rule, payment-card, account, identity, and audit correctness.
- Prove maker-checker, admin bypass policy, reservations, lineage, retention, exports, and tamper evidence.
- Complete Guidewire-like, customer/account/card, lending, payments, and database-plus-mainframe acceptance packs.

### Operations

- Provide one-command validated deployment for the supported model and a no-demo production configuration.
- Publish exact compatibility, prerequisites, native-client setup, resource sizing, and upgrade policy.
- Operate health, readiness, Prometheus metrics, structured logs, correlation IDs, alerts, and support bundles.
- Perform monthly restore evidence during pilots and at least one complete HA/DR exercise before production.

## Commercial proof required

A code-only asset is difficult to defend at USD 600,000. The clearest paths are:

### Recurring-revenue path

- Target USD 100,000-150,000 in annual recurring revenue with acceptable retention and gross margin.
- Prefer multiple customers so one relationship does not represent the complete value.
- Record signed contracts, invoices, collected cash, renewal terms, implementation effort, and support cost.

### Strategic-acquisition path

- Demonstrate how ForgeTDM removes at least 12-24 months of build time for a named buyer profile.
- Quantify connector, mainframe, governance, testing, security, and services replacement cost conservatively.
- Show buyer-specific integration fit, clean IP transfer, documented architecture, and low key-person dependency.
- Obtain a real letter of intent, paid design partnership, or diligence request rather than relying on an estimate.

## Pilot acceptance package

Each pilot must record:

1. Customer problem, systems, volume, privacy classification, acceptance criteria, and exclusions.
2. Exact ForgeTDM build, topology, connectors, drivers, native clients, TLS/authentication, and permissions.
3. Discovery accuracy, masking leakage, RI, business rules, row/byte reconciliation, rejects, and runtime.
4. Cancellation, failure/recovery, rerun, audit, lineage, approval, retention, and operator evidence.
5. Installation time, implementation effort, support requests, user completion rate, and measured value saved.
6. Signed outcome, open limitations, remediation ownership, and permission status for any reference.

## Buyer and customer evidence room

Keep these sanitized, versioned, and reproducible:

- Architecture, threat model, data flows, deployment model, and supported-scope matrix.
- Immutable release artifacts, SBOM, dependency/license report, source ownership, and contributor assignments.
- Test plan, catalog, automated results, defects, security report, connector certificates, and scale benchmarks.
- Install, operations, upgrade, backup/restore, HA/DR, incident, support, and onboarding runbooks.
- Product roadmap, known limitations, release history, pricing, contracts, ARR, retention, support costs, and pilots.
- No passwords, tokens, customer data, unmasked PII, or unverifiable marketing claims.

## Execution roadmap

### Phase 1 - Release discipline (weeks 1-4)

- Make Java tests, frontend build/type/lint, migrations, API compatibility, secret scan, and PostgreSQL smoke
  mandatory for every main-branch change.
- Map every existing automated test to the master catalog and expose the remaining automation gap.
- Freeze supported product terminology, connector status wording, and known limitations.
- Produce an immutable release candidate and clean install/upgrade evidence.

Exit: repeatable build, clean regression, traceability, and no unsupported claim.

### Phase 2 - Enterprise proof (weeks 5-10)

- Complete PostgreSQL, Db2 LUW, Oracle, and SQL Server real-engine packs in priority order.
- Complete independent security review and remediate all release-blocking findings.
- Run accessibility, concurrency, cancellation, recovery, 24-hour endurance, backup/restore, and HA/DR.
- Begin staged scale qualification at 10 million rows, 100 million rows, and 1 TB.

Exit: three enterprise connector lanes lab validated and enterprise nonfunctional gates passed.

### Phase 3 - Banking pilots (weeks 11-18)

- Run two representative banking pilots through UI/self-service, not private engineering shortcuts.
- Measure installation, configuration, data quality, runtime, operator effort, support load, and customer outcome.
- Convert pilot defects into regression tests and rerun the complete affected connector packs.
- Obtain a referenceable outcome where the customer permits it.

Exit: two signed acceptance outcomes and one independently verifiable reference or equivalent evidence.

### Phase 4 - Scale and commercial proof (weeks 19-26)

- Complete the staged 5 TB qualification only on the intended supported topology.
- Finalize pricing, onboarding, support tiers, incident process, SLA language, and release support policy.
- Convert pilots into paid annual agreements and build toward USD 100K-150K ARR.
- Assemble and independently review the evidence room.

Exit: 90/100 readiness, all mandatory gates, commercial proof, and buyer/customer diligence readiness.

## Monthly decision review

Review the scorecard monthly. Every item must be `NOT STARTED`, `IN PROGRESS`, `PASS`, `FAIL`, or `BLOCKED`,
with an owner, due date, evidence link, and defect/remediation link. Do not award points for `IN PROGRESS`.

The decision question is not "How many features were added?" It is: "Which customer risk was removed, how was
it proven, and would an independent buyer or bank accept the evidence?"
