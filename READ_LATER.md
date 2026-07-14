# Read Later

## Converting ForgeTDM into a SaaS app

**What SaaS is:** one hosted product serving many isolated customers ("tenants") over the web, on a subscription, with you handling deployment/updates/uptime/security centrally. The defining property is **multi-tenancy** — one running system safely serving many orgs — not just "in the cloud."

**Where ForgeTDM is today:** single-tenant. One Postgres, one global schema (users, data sources, policies, audit all shared), one bootstrap admin, one deployment. Built to install per customer.

### Core work: multi-tenancy
Three isolation models:
- **Silo** (DB/schema per tenant) — strongest isolation, easy compliance + delete, more ops overhead. Flyway makes per-schema migration feasible.
- **Pool** (shared tables + `tenant_id` on every row) — cheapest/most scalable, but must enforce tenant scoping on every query; use Postgres Row-Level Security (RLS) as the safety net.
- **Bridge** (shared control-plane tables, siloed tenant data) — common for data-heavy tools.

For a PII/security tool, lean **silo or bridge** — customers expect hard isolation.

In our codebase: add an **Organization/Tenant** entity; add `tenant_id` (or per-tenant schema) to tenant-scoped tables (users, data sources, policies, DataScope blueprints, synthetic jobs, snapshots/VDBs, audit); put tenant in the session; enforce scoping centrally (filter/interceptor + RLS) so no query crosses tenants.

### The ForgeTDM-specific wrinkle
The app connects to and operates on **customers' own databases** (masking/subsetting real data; virtualization needs ZFS/Docker hosts). So:
- Enterprises won't send prod data to a multi-tenant cloud. Realistic model = **control plane + data plane split** (like Delphix DCT): we host the SaaS control plane (auth, policies, orchestration, audit, UI); heavy data work runs via a **customer-hosted engine/agent** inside their network. Our cloud never touches raw data — only instructions + metadata/status.
- Customer DB credentials are the crown jewels — per-tenant envelope encryption via KMS, never shared.

### Already have vs missing
- **Have:** session auth, RBAC (users/groups/roles/permissions), tamper-evident audit trail, Postgres + Flyway, job orchestration, approval/governance gates.
- **Missing:** tenant/org model + isolation; self-service signup + org provisioning (today only one bootstrap admin); subscription/billing + metering (Stripe); per-tenant secrets/KMS; quotas + rate limits; control-plane/data-plane agent; cloud deployment (containerize, managed Postgres, object storage instead of local temp dirs, autoscaling); observability + per-tenant metering; compliance (SOC 2 / ISO 27001); custom domains.

### Pragmatic order
1. Tenant model + isolation (Organization, scope tables, RLS) — foundation; nothing safe without it.
2. Self-service onboarding (signup creates org + first admin).
3. Deployment (containerize, object storage, managed Postgres, secrets vault/KMS).
4. Data-plane agent (real data stays in customer environment).
5. Billing + metering + quotas.
6. Compliance + observability (audit is a head start).

Realistically a months-long re-platforming; step 1 + the data-plane decision matter most. Auth/RBAC/audit foundations mean not starting from zero.

**Highest-leverage first slice:** add an `Organization` entity + Flyway migration introducing `tenant_id` on core tables + wire tenant scoping into the session/security filter.
