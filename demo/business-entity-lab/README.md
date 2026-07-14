# ForgeTDM Business Entity Acceptance Lab

This lab leaves a reviewable Customer 360 Business Entity in ForgeTDM across three real database engines:

| Application | Engine | Schema | Tables | Rows |
|---|---|---|---:|---:|
| Core Banking | PostgreSQL | `be_core` | 20 | 198,825 |
| Card Servicing | Oracle | `BE_CARDS` | 13 | 144,200 |
| Digital Engagement | MySQL | `digital_engagement` | 10 | 94,000 |
| **Total** | | | **43** | **437,025** |

## Review The Finished Lab

1. Start the backend on `http://localhost:8088`.
2. Start the new UI from `frontend` with `npm.cmd run dev`.
3. Open `http://localhost:3000/business-entities`.
4. Select **BE Lab - Customer 360**.
5. Review Model, Identity, Freshness, Snapshots & reservations, Micro-DB, Deliver, and Govern.
6. Open `business-entity-acceptance-report.html` for counts, DDL, screenshots, fixes, and the recorded walkthrough.

## Recreate ForgeTDM Artifacts

The setup is idempotent. It updates existing lab artifacts rather than creating duplicate members, identities, catalog assets, or saved jobs.

```powershell
& "D:\forgetdm - Copy\demo\business-entity-lab\configure-forgetdm.ps1"
```

It creates or reconciles:

- Three tested data-source connections.
- Three DataScope blueprints with 20, 13, and 10 table profiles.
- One 43-member Business Entity.
- 100 canonical identity subjects with 300 cross-application links.
- One three-system freshness policy.
- One immutable 437,025-row evidence snapshot.
- One active Customer 360 Micro-DB for `customer_no=CUST-000042`: 43 encrypted masked fragments, 265 related rows, retained versions, freshness watermarks, access grants, and lineage.
- One maker-checker approval.
- One approved three-slice execution plan.
- One approved Micro-DB execution plan and immutable package pinned to the current capsule version.
- One ACTIVE visual flow and debugger evidence.
- One READY self-service package with immutable versions.

## Run Acceptance

```powershell
& "D:\forgetdm - Copy\demo\business-entity-lab\verify-forgetdm.ps1"
```

The script performs connector diagnostics, identity resolution from every application, freshness, snapshot, Micro-DB encryption/masking/consistency checks, governance negative testing, flow validation/debugging, saved-package checks, and bounded DataScope previews. Results are written to `acceptance-results.json`.

Regenerate the HTML report after a new acceptance run:

```powershell
& "D:\forgetdm - Copy\demo\business-entity-lab\generate-report.ps1"
```

## Saved Jobs For Future Use

In the new UI, open **Business Entities > BE Lab - Customer 360 > Deliver > Run & packages**.

- **BE Lab - Customer 360 QA Provision** is the approved saved execution plan.
- **BE Lab - Customer 360 Release Package** is the reusable self-service/scheduler package.
- **BE Lab - CUST-000042 Micro-DB v5 Provision** is pinned to capsule 2, version 5, and launches through the Micro-DB engine without rereading the three source applications.
- **BE Lab - CUST-000042 Micro-DB v5 Package** is the reusable immutable runner for that entity capsule.
- Use **Manage** to inspect immutable versions and obtain the shell runner.

The plan is intentionally `PLAN_ONLY`. The lab DataScopes currently point source and target to the same schemas, so a physical replace/delete launch would mutate the fixture. Configure dedicated targets, review the three application slices, and obtain a new approval before launching.

## Rebuild Physical Fixtures

These scripts are destructive only to their named lab schema/database/user. Run them with an administrative account for the corresponding local engine.

```powershell
psql.exe -h localhost -p 5433 -U postgres -d sourcedb -f "D:\forgetdm - Copy\demo\business-entity-lab\postgres-core-banking.sql"
sqlplus.exe system/<system-password>@XE '@D:\forgetdm - Copy\demo\business-entity-lab\oracle-card-servicing.sql'
Get-Content -Raw "D:\forgetdm - Copy\demo\business-entity-lab\mysql-digital-engagement.sql" | mysql.exe -h localhost -P 3306 -u root -p
```

The full DDL and deterministic data-generation statements are also embedded in the HTML report.

## Backend Start

```powershell
$env:FORGETDM_DB_URL = "jdbc:postgresql://localhost:5433/forgetdm"
$env:FORGETDM_DB_USER = "forgetdm"
$env:FORGETDM_DB_PASS = "forgetdm"
$env:FORGETDM_MASKING_SECRET = "pick-a-long-random-secret"
mvn spring-boot:run
```

No paid service is used by this lab or its UI recording.
