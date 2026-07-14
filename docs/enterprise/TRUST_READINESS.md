# Enterprise trust readiness

This is an evidence checklist, not a claim of certification or market recognition.

Connector claims are governed by the [connector certification matrix](CONNECTOR_CERTIFICATION_MATRIX.md).
The matrix starts from the honest current baseline and promotes only exact, evidenced database lanes.

## Product evidence available now

- RBAC, maker-checker approval, API-token revocation, audit-chain verification, validation reports, and run lineage.
- Tenant-isolated deployment cells, HA manifests, health/metrics endpoints, graceful shutdown, scheduler leases,
  database backup/restore runbook, and connector preflight evidence.
- OpenAPI documentation, CI examples, self-service requests, signed webhooks with retries, and immutable job history.

## Organizational evidence that must be created outside code

| Evidence | Owner | Release gate |
| --- | --- | --- |
| Named support team, hours, escalation tree, severity definitions and response targets | Support lead | Required before first production client |
| Security incident response plan and annual tabletop | Security owner | Required |
| Penetration test, dependency/SBOM review and remediation report | Security owner | Required |
| Customer onboarding and execution of the connector certification matrix/playbook | Services lead | Required |
| Backup restore and regional failover drill evidence | Platform owner | Required |
| Customer references and permission to use them | Customer success | Earned, never fabricated |
| Analyst submissions/recognition (including Gartner) | Product marketing | External process, never represented without evidence |
| Partner qualification, training and support boundaries | Alliances lead | Required before listing a partner |

## SLA template inputs

Define service hours, severity levels, first-response target, restore target, update cadence, exclusions, data
handling boundaries, maintenance notification, escalation contacts, and service-credit language with counsel.
Do not publish availability percentages until monitoring history and an operating team can support them.
