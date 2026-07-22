# DEF-0032 - Nested and runtime objects bypass cross-group isolation

| Field | Value |
|---|---|
| Severity | HIGH (S1) |
| Status | CLOSED - fixed, fully retested, and independently reconciled 2026-07-21 |
| Found by story | RBAC-002 (02, 03, 04, 05, 06) |
| Component | DataScope child resources and versions; provisioning runtime jobs; referenced governed objects |
| Found on | Source audit and focused negative tests, 2026-07-20 |

## Summary

The V61 tenancy boundary protects top-level data sources, policies, DataScope blueprints, and
reservations, but several subordinate and runtime paths did not resolve their parent object through
that boundary. A caller with the route permission could therefore guess a child or runtime id and
reach another group's object without first proving access to its owner.

Confirmed paths include:

- deleting a table profile without resolving the parent DataScope blueprint;
- deleting a column override or tool-defined primary key directly by child id;
- updating or deleting a tool-defined relationship directly by child id;
- reading, comparing, or restoring a DataScope version directly by version id;
- listing, reading, canceling, retrying, deleting, sampling, approving, or rejecting provisioning
  jobs without object tenancy on `provision_jobs`.

The wider RBAC-002 audit is also checking indirect references such as a hidden policy or data source
id supplied to an otherwise visible blueprint or run request.

## Impact

Cross-group reads can expose frozen masking rules, source metadata, row samples, job diagnostics,
and execution state. Cross-group writes can alter extraction relationships, restore another group's
blueprint, cancel a run, or change an approval decision. RBAC-002 classifies any such exposure as an
S0/S1 release blocker.

## Required resolution

1. Every child-id route must load the child, authorize its parent, and only then read or mutate it.
2. Runtime jobs must carry owner user, owner group, and visibility, with scoped list and guarded id
   access; legacy jobs must remain available through an explicit compatibility policy.
3. Indirect object references must be authorized before being persisted or executed.
4. Retain two-group negative tests, same-group/shared/admin positive controls, unchanged-object
   checks, and structured denial-audit evidence.
5. Run the full backend/frontend regression and independent review before closure.

## Work log

- 2026-07-20: added parent authorization to DataScope profile, override, custom-PK, relationship,
  and version paths.
- 2026-07-20: added focused tests proving denied requests cannot mutate child rows, cascade-delete
  traversal rules, or write a restore snapshot; same-group mutation remains allowed.
- 2026-07-21: completed provisioning/runtime ownership, indirect-reference validation, mapping,
  mainframe, unstructured, validation, business-entity, and audit tenancy coverage.
- 2026-07-21: focused isolation suite passed 90/90; live HTTP matrix passed 35/35.
- 2026-07-21: complete backend regression passed 503 tests with no failures or errors; the only
  skip was the unrelated opt-in synthetic scenario pack. Frontend route generation and TypeScript
  typecheck passed.
- 2026-07-21: evidence was reconciled case-by-case against RBAC-002-01 through RBAC-002-09.

## Closure

All required resolution items are proven in `docs/testing/evidence/RBAC-002-EVIDENCE.md`. No
RBAC-002 test or acceptance criterion is deferred or HARD-PASS. DEF-0032 is closed.
