# ForgeTDM Guidewire-Style Acceptance Roadmap
## Phase 1 - Infrastructure and Model

- Verify Oracle, PostgreSQL, SQL Server, ForgeTDM backend, and Next.js UI health.
- Install the free SQL Server Express engine when absent.
- Create source and masked-target schemas for Policy, Claims, and Billing.
- Apply the 150-table DDL and verify primary keys, foreign keys, checks, and table counts.

**Exit gate:** all six schemas are browsable from ForgeTDM and each application reports exactly 50 source tables.

## Phase 2 - Synthetic Designs

- Import Oracle Policy tables and learn live metadata.
- Import SQL Server Claims tables and learn live metadata.
- Import PostgreSQL Billing/Contacts tables and learn live metadata.
- Review generated assignments, add controlled literals/reference lists, set row distributions, and preserve FK ownership.
- Configure deterministic seed, bounded numeric/date generators, Luhn-valid unique cards, and cross-application identity keys.
- Save one reusable job per application.

**Exit gate:** preview passes for all three designs with 750,000 total planned rows.

## Phase 3 - Generate and Reconcile

- Run the three jobs from the new UI.
- Observe table and partition progress; exercise cancel/retry on a controlled run.
- Reconcile source counts, constraints, FK integrity, business keys, cards, and generator bounds.

**Exit gate:** all source-side acceptance checks pass and evidence is retained.

## Phase 4 - Discover and Govern

- Scan all three source schemas with insurance, PCI, and privacy scopes.
- Review findings, approve true PII, reject false positives, and export findings.
- Create deterministic masking policies for linked identities and format-preserving rules for operational fields.
- Record explicit unmasked decisions only for approved non-sensitive fields.

**Exit gate:** every approved sensitive column has a policy or a governed exception.

## Phase 5 - Map, Subset, and Provision

- Create DataScope blueprints for Policy, Claims, and Billing.
- Configure driver tables, Q1 parent pull, Q2 child cascade, per-edge traversal, filters, and row caps.
- Validate table/column maps and target schemas.
- Preview plans, save provisioning jobs, and provision masked targets.

**Exit gate:** target counts reconcile, no orphan exists, and cross-application keys remain consistent.

## Phase 6 - Enterprise Acceptance

- Rerun saved jobs without rebuilding flows.
- Validate audit, lineage, maker-checker evidence, failure messages, restart behavior, and downloadable reports.
- Capture UI screenshots and publish the final HTML report with defects, fixes, residual risks, and operating steps.

**Exit gate:** all mandatory requirements pass or have an explicit evidence-backed exception.

## Scale Progression

| Gate | Rows/application | Purpose |
|---|---:|---|
| Workstation acceptance | 250,000 | Functional and operational proof on current hardware |
| Performance baseline | 1,000,000 | Batch, partition, memory, and loader tuning |
| Enterprise rehearsal | 10,000,000+ | Dedicated database hosts and native loaders |
| Multi-terabyte certification | Data-volume based | Production-like infrastructure, HA/DR, restore, and sustained throughput |

The 5 TB claim is not made from a laptop run. It requires the final certification gate on production-equivalent infrastructure.
