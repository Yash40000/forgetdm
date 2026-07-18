# DEF-0012 — Exporting the audit trail is not itself audited

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | **CLOSED** — fixed and verified live 2026-07-18 |
| Found by story | AUD-001 — case AUD-001-04 |
| Component | `io.forgetdm.audit.AuditController.export()` |

## Summary

`GET /api/audit/export.csv` extracts the entire audit trail as a file and records **nothing**.
Verified live: event total was 3,012 before the export and 3,012 after, and no `*EXPORT*` action
exists among the 95 distinct actions in the trail.

AUD-001-04 requires: "Export event records actor, object, purpose/format/count where safe, and
outcome **before delivery**."

## Impact

Bulk extraction of the complete security record — including who did what, when, and from which IP —
leaves no trace. An insider can exfiltrate the whole audit trail invisibly. This is the one export
that most needs to be tracked.

The same gap likely applies to other export paths (synthetic export, job packages) — AUD-001-04 was
only executed against the audit CSV.

## Recommended fix

Record an `AUDIT_EXPORTED` event **before** writing the body, capturing actor, applied filters,
format (`csv`), row count, whether the result was truncated, and outcome. Then sweep the other
export endpoints for the same omission.

## Resolution (2026-07-18) — verified live

`AUDIT_EXPORTED` is now recorded **before** the body is written, with structured resource identity
and counts in metadata.

Live sample: `action=AUDIT_EXPORTED`, `resourceType=audit`, `resourceName=audit-trail.csv`,
`detail="Exported 3016 of 3016 matching events (csv)"`, metadata carrying
`{format, exported, matched, limit, truncated}`. Export event count went 0 → 1.

Outcome deliberately stays within the modelled `SUCCESS`/`FAILURE` domain (the facets expose only
those two) — truncation is carried in the detail and metadata rather than inventing a third value
that would pollute the outcome filter.

**Still open:** the sweep of *other* export paths (synthetic export, job packages) was not performed —
AUD-001-04 was executed against the audit CSV only.
