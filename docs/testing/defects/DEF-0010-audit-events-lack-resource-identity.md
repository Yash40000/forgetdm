# DEF-0010 — Audit events carry no resource identity or correlation context

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | **OPEN** |
| Found by story | AUD-001 — case AUD-001-01 |
| Component | `io.forgetdm.audit.AuditService.log()` call sites across the app |

## Summary

V54 added `resource_type` / `resource_id` / `resource_name` / `metadata` columns and a structured
`AuditService.record(...)` entry point, but essentially every call site still uses the legacy 3-arg
`audit.log(actor, action, detail)`, which leaves those columns `NULL`.

Measured live across the whole trail (3,010 events):

| Field | Populated |
|---|---|
| `resourceType` | **2** |
| `resourceId` | **2** |
| `resourceName` | **2** |
| `metadata` | **0** |

Consequence: `GET /api/audit?resourceType=policy` returns **0** — the documented filter is dead, and
the resource is only recoverable by string-matching free text in `detail`.

AUD-001-01 requires each material event to include "actor, action, **resource identity**, outcome,
timestamp, and **correlation context**". Actor/action/outcome/timestamp are present and correct;
resource identity and correlation context are effectively absent.

## Impact

Cannot answer basic forensic questions ("everything that happened to policy 34") without brittle text
matching. Blocks the AUD-001 coverage matrix.

## Recommended fix

Migrate material call sites to `record(...)` with resourceType/resourceId/resourceName — prioritise
the security-relevant ones (policy, data source, DataScope blueprint, reservation, security user/group,
provision/synthetic jobs, ACCESS_DENIED). Add a correlation id (request id) to `metadata`.
