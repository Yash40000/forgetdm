# Referential Integrity & Keys Registry — TODO

A tool-level, reusable registry of primary keys and foreign-key relationships, scoped per user / group /
global, consumed by masking, synthetic generation, subsetting and validation. Decided 2026-06-22:
additive + phased rollout; Private/Group/Global with precedence **user > group > global > live DB metadata**;
definitions bound to **data source + schema + table**.

## Phase 1 — DONE (additive, non-breaking)
- [x] `V25` migration: `ri_primary_key` + `ri_relationship` (owner/visibility/datasource scoping, cardinality on relationships).
- [x] `RiRegistryService` + `RiController` — CRUD, visibility-precedence `resolve`, `my-groups`.
- [x] RBAC: `ri.read` / `ri.manage` in `RoleDefinition` + `/api/ri` mapping in `AccessControlFilter`.
- [x] New sidebar group "Referential Integrity → Keys & Relationships" (after Connect & Discover, before Mask) + page + CRUD UI with scope selector.

## Phase 2 — wire consumers (additive, with fallback to current behavior)
- [ ] **Synthetic Data** (pilot): consume `/api/ri/resolve` for FK relationships + cardinality + PKs, merged with live DB metadata as the floor (registry overrides live). Keep current auto-detect when no registry entry exists.
- [ ] **Subsetting**: use registry relationships for traversal where present.
- [ ] **DataScope**: read relationships/PKs from the registry; keep blueprint-level overrides on top.
- [ ] **Masking / Validation**: use registry PKs to avoid masking key columns and to drive referential validation.

## Phase 3 — migrate & retire embedded RI (only after Phase 2 is proven)
- [ ] Provide a one-click "import DataScope blueprint relationships into the registry" migration.
- [ ] Once parity confirmed, retire DataScope's embedded RI editing in favor of the central registry (read-only view in DataScope that links to the registry).

## Later / nice-to-have
- [ ] "Learn from DB" button on the RI page: pre-populate PK/FK definitions from live metadata so the user only edits exceptions.
- [ ] Bulk import/export of RI definitions (JSON) for environment promotion.
- [ ] Versioning + audit diff of RI definitions.
- [ ] GLOBAL publish approval (maker-checker) for regulated environments.
