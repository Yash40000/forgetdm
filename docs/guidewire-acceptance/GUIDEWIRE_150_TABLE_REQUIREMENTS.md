# ForgeTDM Guidewire-Style 150-Table Acceptance Requirements

## 1. Purpose

Prove, from the new ForgeTDM UI, that a tester can design, generate, discover, mask, subset, provision, rerun, cancel, and audit a realistic cross-application insurance dataset without invoking ForgeTDM APIs directly.

This is an original Guidewire-style acceptance model. It represents Policy, Claims, Billing, and Contact behaviors without copying proprietary Guidewire schemas or data.

## 2. Test Applications

| Application | Database | Source schema | Masked target | Tables | Initial rows |
|---|---|---|---|---:|---:|
| Policy Operations | Oracle XE | `BE_CARDS` (`PC_*`) | `BE_CARDS` (`PT_*`) | 50 | 250,000 |
| Claims Operations | SQL Server Express | `gw_claim_src` | `gw_claim_tgt` | 50 | 250,000 |
| Billing and Contacts | PostgreSQL | `gw_billing_src` | `gw_billing_tgt` | 50 | 250,000 |

The initial 750,000-row run is sized for this 10 GB workstation. Every design must remain reusable with larger row counts, partition sizes, and worker counts.

## 3. Data Model Requirements

1. Each application contains exactly 50 physical tables with database-enforced primary and foreign keys.
2. Shared business identities use stable `public_id`, `party_key`, `policy_number`, and `claim_number` values across applications.
3. The models include one-to-one, one-to-many, many-to-many, optional, self-referencing, composite-unique, and deep dependency chains.
4. Rows include effective/expiration dates, type codes, version columns, retired flags, tenant/partition keys, audit timestamps, and optimistic-lock fields.
5. Data types include character, Unicode, integer, decimal, Boolean/flag, date, timestamp, timezone, CLOB/text, BLOB/binary metadata, and JSON where the engine supports it.
6. Business checks include nonnegative amounts, bounded percentages, valid date ranges, controlled statuses, and payment-card length rules.
7. PII/PCI data includes names, birth dates, SSNs/tax identifiers, addresses, phones, emails, bank accounts, routing numbers, payment cards, driver licenses, claim notes, and document metadata.
8. Payment-card numbers must pass Luhn validation and be unique for every generated CCN row within a job.

## 4. UI-Only Operating Requirements

1. Connections are created and tested from **Data Sources** in the new UI.
2. Live schemas are imported from **Synthetic Data > Source tables** using browse/import controls.
3. Generator assignments, row counts, FK mappings, literal values, and distributions are reviewed and saved in the source-table workspace.
4. Target, load action, prep action, batch size, worker count, and partitions are configured in **Output & execution**.
5. Every reusable design is saved with a job name of at least eight characters.
6. Generation is launched from the UI and observed through table- and partition-level live status.
7. Discovery, policy creation, table/column mapping, preview, masking, and provisioning are performed from their new UI workspaces.
8. No direct ForgeTDM REST call, database-side row generator, or external bulk data insert is allowed for acceptance data creation or provisioning.

Empty schema DDL may be bootstrapped as test infrastructure. Oracle XE uses separate `PC_*` source and `PT_*` target tables in the existing `BE_CARDS` schema because the registered user is intentionally not a DBA. All acceptance rows must be produced and moved by ForgeTDM.

## 5. Acceptance Criteria

| Area | Pass criterion |
|---|---|
| Inventory | 50 source and 50 target tables exist for each application |
| Generation | 750,000 planned rows complete with 0 unhandled generator failures |
| Referential integrity | 0 orphaned child rows and 0 disabled/untrusted constraints |
| Uniqueness | 0 duplicate PK, business-key, or generated payment-card values |
| Data quality | 100% of enforced CHECK constraints pass |
| Discovery | Required PII/PCI classes are found and available for review |
| Masking | Approved sensitive fields change; deterministic joins remain consistent |
| Provisioning | Target counts reconcile to the approved plan and filters |
| Cross-application consistency | Shared party/policy/claim keys resolve across all three systems |
| Operations | Cancel, retry, saved-job rerun, and failure evidence are visible |
| Governance | User, job, policy, seed, timestamps, row counts, and decisions are auditable |
| Performance | UI remains responsive; no JVM heap-space error; progress advances by real work |

## 6. Negative and Recovery Scenarios

1. Invalid source, schema, table, or target names are rejected before launch.
2. A target-column precision overflow is prevented by generator bounds or reported at the exact table/column.
3. An FK configuration that would create an orphan is blocked during preview.
4. A duplicate business key or payment card cannot be generated within the job.
5. A running partitioned job can be cancelled and reaches a terminal cancelled state.
6. A failed partition can be retried without duplicating previously committed rows.
7. A saved job can be loaded and rerun without rebuilding the design.
8. Unmasked provisioning is explicit, governed, and never inferred from a missing policy.
9. A database outage produces a bounded failure with retained evidence rather than an endless running state.
10. A target schema mismatch fails preflight and does not report a false success.

## 7. Evidence Deliverables

- Connection inventory and test results
- 150-table manifest and DDL
- Saved synthetic jobs and generation run IDs
- PII discovery findings and exported report
- Masking policy and table/column mapping evidence
- Provision preview and final target reconciliation
- RI, uniqueness, constraint, and cross-system identity checks
- Screenshots and a final HTML acceptance report
