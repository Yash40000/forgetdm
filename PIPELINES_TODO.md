# Pipelines feature — scope / TODO

**Status:** planned (added 2026-07-06). Not started.

## Goal
A first-class, end-to-end TDM **Pipeline**: a named, ordered sequence of typed stages spanning the whole
lifecycle, runnable in one click, on a schedule, or from CI via PS1/SH runners. Generalizes the existing
mapping **Workflow** engine (`io.forgetdm.mapping.WorkflowEntity` = "ordered pipeline of steps") across the
whole tool, reusing the DataScope saved-job scheduler + batch-runner machinery.

## Stage types
`DISCOVER`, `SUBSET`, `MASK`, `PROVISION`, `SYNTHETIC`, `SQL`, `VALIDATE`.
Each stage delegates to an existing service rather than reimplementing:
- DISCOVER  → `DiscoveryService` (scan a source/schema)
- SUBSET    → `SubsetService`
- MASK / PROVISION → `ProvisioningService.submit` (a saved DataScope job or an inline spec) — **keeps the
  existing maker-checker approval + prod guardrails**
- SYNTHETIC → `SyntheticGenService` (a saved synthetic job)
- SQL       → `QueryService` (raw statement against a chosen datasource; guard with SubsetService.guardFilter-style checks)
- VALIDATE  → row-count / assertion query; fail the stage (and pipeline, if onError=STOP) when the assertion is false

## Data model
`V36__pipelines.sql` + `PipelineEntity` / `PipelineRepository`. Owner-private (mirror `datascope_saved_jobs`):
- id, owner_user_id, owner_username, name (unique per owner), description
- `stages_json`  — `[{type, label, onError: STOP|CONTINUE, dependsOn?: [labels], ...stage params}]`
- `last_run_json` — `{status, startedAt, finishedAt, stages:[{label, type, status, rows, error, elapsedMs}]}`
- schedule: schedule_cron, schedule_zone, schedule_enabled, next_run_at, last_scheduled_run_at
- last_run_at, created_at, updated_at

## Run engine (`PipelineService`)
- Execute stages in order (topological if `dependsOn` used), background thread, poll `GET /{id}` → last_run_json.
- `onError: STOP` aborts the pipeline; `CONTINUE` records the failure and proceeds.
- Per-stage progress written incrementally (throttled) like `ProvisioningService` table states.
- Reuse existing approval gates: a PROVISION stage that lands AWAITING_APPROVAL parks the pipeline (surface it).

## API (`/api/pipelines`)
`GET` list, `GET /{id}`, `POST` save, `PUT /{id}`, `DELETE /{id}`, `POST /{id}/run`,
`PUT /{id}/schedule`, `POST /schedule/preview`.
Scheduler: copy the `DataScopeJobService.runDueSchedules()` `@Scheduled(30s)` sweep (CronExpression,
advance next_run_at BEFORE running, isolate per-pipeline failures).

## CI/CD runners
Reuse the PS1/SH generators from `app.js` (`dsSavedJobPowerShellScript` / `dsSavedJobBashScript`), pointed at
`/api/pipelines/{id}/run` then polling `/api/pipelines/{id}` — same login/token, same exit codes (0 ok, 1 failed,
2 cancelled, 3 awaiting-approval).

## Frontend
New **Pipelines** page: visual builder to add/reorder typed stages, per-stage param pickers (choose a DataScope,
policy, saved synthetic job, SQL, or validation), onError toggle, optional dependency links; Save / Run /
Schedule / PS1 / SH / Rename / Delete; live per-stage run monitor from last_run_json. Reuse the
`ds-saved-job*` list/schedule/runner JS + CSS patterns.

## Verify
Unit-test stage sequencing, onError STOP/CONTINUE, dependency ordering, per-stage progress, cron preview;
JDK17 compile; `node --check`; one end-to-end DISCOVER→SUBSET→MASK→PROVISION sample.

## Reuse map (don't reinvent)
- Scheduler + runners + owner-private CRUD → `DataScopeJobService` / `DataScopeJobController`
- Ordered-steps + last_run_json progress → `mapping.WorkflowService` / `WorkflowEntity`
- Approval/governance → `ProvisioningService.submit`
