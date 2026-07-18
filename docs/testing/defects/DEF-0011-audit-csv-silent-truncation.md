# DEF-0011 — Audit CSV export silently truncates at 5,000 rows

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | **CLOSED** — fixed and verified live 2026-07-18 |
| Found by story | AUD-001 — case AUD-001-07 |
| Component | `io.forgetdm.audit.AuditController.export()` |

## Summary

The CSV export hard-caps the result set:

```java
PageRequest.of(0, 5000)
```

and returns the body with **no indication that a cap was applied** — no total count, no truncation
marker, no header. Once the filtered set exceeds 5,000 events an operator receives exactly 5,000 rows
that are indistinguishable from a complete export.

AUD-001-07 requires: "Documented limit is enforced **without truncation being mistaken for a complete
export**." Live today the trail is 3,011 rows (under the cap) so the export is complete — the defect
is latent but certain.

## Impact

Compliance evidence may silently omit records. An auditor cannot tell a partial export from a full one.

## Recommended fix

- Return the matched total and the applied cap in response headers
  (e.g. `X-Total-Count`, `X-Export-Limit`, `X-Truncated: true`).
- Emit a final CSV comment row when truncated, e.g.
  `# TRUNCATED: 5000 of 12345 matching events exported — narrow the filter or use pagination`.
- Better: stream the full result set (`StreamingResponseBody`) so no cap is needed, or require an
  explicit `confirmTruncation=true` when the match exceeds the cap.

## Resolution (2026-07-18) — verified live

`export()` now computes `matched` vs `exported`, emits response headers and, when capped, appends a
trailing `# TRUNCATED: exported N of M …` comment row so a human opening the CSV cannot miss it.

Live: `X-Total-Count: 3016`, `X-Exported-Count: 3016`, `X-Export-Limit: 5000`, `X-Truncated: false`
— a complete export is now positively identified as complete. (The trail is still below the cap, so
the truncated path is covered by construction and by the `X-Truncated` flag rather than live data.)
