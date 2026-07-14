# Enterprise automation and integration

## What the Automation page is for

Open **Automation** in the new UI at `http://localhost:3000/automation`. The page brings together the controls
that let an approved ForgeTDM design participate in an enterprise delivery process:

1. **API tokens** authenticate Jenkins, Azure DevOps, GitHub Actions, shell runners, or an enterprise scheduler.
2. **Saved DataScope jobs** own the schedule because the source, target, mappings, masking, and approval context
   must remain reviewable beside the schedule. Open DataScope, then Saved jobs, to preview and enable a cron.
3. **Self-service** keeps request purpose, environment, volume, launch time, and maker-checker approval in the flow.
4. **Integrations** publish lifecycle events to approved HTTPS gateways for ServiceNow, Jira, Azure DevOps, or a
   generic listener.
5. **Delivery activity** shows queued, delivered, retrying, and dead deliveries without exposing event payloads.

This separation is intentional: Automation owns credentials and cross-system signals; the saved job owns what
runs; Self-service owns who requested it and who approved it.

## Safe review walkthrough

The local review endpoint named **Review demo - QA approval events** is disabled by default. It documents a
realistic ServiceNow integration without sending anything until a reviewer supplies an approved gateway URL and
secret environment variable.

1. Open **How it works** and follow the four operating steps.
2. Open **API tokens**. The revoked smoke-test token demonstrates the full create, authenticate, and revoke
   lifecycle without leaving usable credentials behind.
3. Open **Integrations**, inspect the disabled review endpoint, and select **Edit** to see its event subscription.
4. Replace the example URL and secret environment variable only when an approved receiver is available, enable
   it, save, and select **Test**.
5. The page opens **Delivery activity** automatically. A successful receiver changes from `PENDING` to
   `DELIVERED`; failures show retry timing and the final error. Failed deliveries can be retried after the endpoint
   is corrected and enabled.

### Practical benefits

- **Repeatability:** UI, scheduler, and pipeline calls reuse the same saved and validated design.
- **Least privilege:** tokens inherit the owner's current roles, expire, stop when the user is disabled, and can
  be revoked without changing a shared password.
- **Governance continuity:** automation does not bypass approval, masking, relationship, or target guardrails.
- **Operational evidence:** delivery IDs, attempts, timestamps, errors, audit events, and durable outbox state make
  integration failures visible.
- **Reduced manual work:** approved refreshes can run overnight or during a release pipeline while testers use the
  Self-service catalog instead of rebuilding provisioning flows.

## Authentication and API documentation

Every signed-in user can create a named, expiring API token at `POST /api/auth/tokens`. The clear token is
returned once; ForgeTDM stores only its SHA-256 hash. Tokens inherit the user's current RBAC roles, stop working
when the user is disabled, and can be revoked at `DELETE /api/auth/tokens/{id}`.

OpenAPI JSON is at `/v3/api-docs`, YAML at `/v3/api-docs.yaml`, and the development Swagger UI at
`/swagger-ui.html`. Set `FORGETDM_OPENAPI_UI_ENABLED=false` in production when interactive API exploration is
not allowed.

## Self-service flow

1. A DataScope owner saves and validates a job, then selects **Self-service** in Saved jobs.
2. A tester opens **Self-service**, chooses the governed template, target environment, and business purpose.
3. A different authorized user approves or rejects with a signed decision note.
4. The requester selects **Provision now**. The immutable template runs through the normal provisioning and
   PII governance gates; the request stores the resulting run id.

CI examples live under `integrations/` and call the same flow using a revocable API token. They wait for human
approval instead of bypassing it.

## Jira, ServiceNow, Azure DevOps and generic webhooks

Administrators configure `POST /api/integrations` with `kind`, HTTPS `url`, comma-separated `eventTypes`, and
an optional `secretEnv`. The secret value stays in the process secret store, not the database. Deliveries use
`X-ForgeTDM-Event`, `X-ForgeTDM-Delivery`, and optional HMAC-SHA256 `X-ForgeTDM-Signature-256` headers.

Delivery is durable in PostgreSQL, single-winner across replicas, retried with exponential backoff, and moved
to `DEAD` after eight failed attempts. Jira/ServiceNow should terminate at an approved integration gateway or
incoming-webhook adapter that maps the ForgeTDM event envelope to the organization's issue/request schema.

The Automation page exposes delivery metadata but deliberately does not return `payload_json`; test purposes,
environment details, and other workflow context stay out of the operational list view.

For local HTTP receivers only, set `FORGETDM_INTEGRATIONS_ALLOW_HTTP=true`. Production endpoints require HTTPS.
