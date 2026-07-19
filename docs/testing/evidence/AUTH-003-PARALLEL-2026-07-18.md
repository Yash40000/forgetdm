# AUTH-003 Parallel Evidence Review - 2026-07-18

## Scope and isolation

- Story: `AUTH-003 - Expired Session Redirect and Draft Recovery`
- Review type: independent evidence-only quality lane
- Source snapshot: current working tree based on commit `16b6e3758ae249aaa3ed85ee89f90b82762cf2c4`
- Executed at: `2026-07-18T13:58:56-04:00`
- Isolation: tests ran from a local snapshot outside the coordinator checkout. No production, test, story, index, or defect file was edited.
- Decision rule: a case is `PASS` only when direct executable evidence proves the complete expected result. Narrative claims and source inspection alone do not count as a pass.

## Evidence inventory reviewed

- `docs/testing/cases/ready/AUTH-003.md`
- `docs/testing/evidence/AUTH-003-EVIDENCE.md`
- `docs/testing/defects/DEF-0003-expiry-redirect-destroys-unsaved-draft.md`
- `docs/testing/defects/DEF-0004-next-open-redirect-backslash-bypass.md`
- `src/test/java/io/forgetdm/security/Auth003FrontendBehaviorTest.java`
- `src/test/java/io/forgetdm/security/Auth003AccessControlFilterTest.java`
- Relevant authentication, redirect, unsaved-draft, and app-shell implementation files referenced by those tests

The existing evidence document is internally inconsistent: its heading says `8/8 cases executed live`, while its result table still labels cases 01, 02, 03, 06, and 07 as code-verified/pending and case 08 as requiring live capture. Its exit checklist also remains entirely unchecked and contains the required Playwright and second-reviewer gates.

## Commands and actual results

### Targeted AUTH-003 behavior suite

```powershell
mvn -q "-Dtest=Auth003FrontendBehaviorTest,Auth003AccessControlFilterTest" test
```

Result: `PASS` (exit code 0), 5 tests executed, 0 failures, 0 errors, 0 skipped.

| Test suite | Tests | Result | Time |
|---|---:|---|---:|
| `Auth003FrontendBehaviorTest` | 3 | PASS | 8.144 s |
| `Auth003AccessControlFilterTest` | 2 | PASS | 9.236 s |

Executed test methods:

- `fiveConcurrent401ResponsesStartExactlyOneSameOriginLoginNavigation`
- `returnPathValidatorAcceptsLocalQueryAndRejectsExternalVectors`
- `unsavedGuardInstallsPromptAndIsWiredToBothDraftWorkspaces`
- `loginEndpoint401PassesThroughOnceWithoutFilterRedirectLoop`
- `expiredSessionReturnsStructured401WithoutRedirectOrProtectedHandlerExecution`

### Frontend type gate

```powershell
cd frontend
npm.cmd run typecheck
```

Result: `FAIL` (exit code 1). Next route type generation completed, then TypeScript failed:

```text
src/features/catalog/hooks.ts(67,7): error TS2322
... meta ... is not assignable to type Record<string, string>
```

This is outside AUTH-003 behavior, but it fails AUTH-003's explicit exit gate that the frontend typecheck be green.

### Frontend production build attempts

```powershell
npm.cmd run build
npm.cmd run build -- --webpack
```

Result: `ENVIRONMENT-BLOCKED`, not a product verdict. The isolated lane reused the coordinator's installed dependencies through a directory junction. Turbopack rejected the out-of-root junction; webpack then resolved Next entry points back to the coordinator path. No production build pass can be claimed from this lane. The independently executed TypeScript gate already produced a real source error before this build-only isolation limitation.

### Playwright inventory

No Playwright dependency, configuration, or test/spec file exists in the frontend package. Therefore the story's explicit requirement to add Playwright coverage is not met.

## Acceptance criteria mapping

| Case | Status | Direct evidence | Gap or reason |
|---|---|---|---|
| AUTH-003-01 | PASS | `fiveConcurrent401ResponsesStartExactlyOneSameOriginLoginNavigation` executes `apiFetch` with a 401 and asserts exactly one assignment to `/login?next=` containing the original path and query. | Complete at component behavior level. |
| AUTH-003-02 | BLOCKED | The same test executes five concurrent 401 responses and proves one redirect assignment. | It does not observe browser navigation events, redirect loops after navigation, or toast count. The required `no toast storm` result is unproven. |
| AUTH-003-03 | BLOCKED | `returnPathValidatorAcceptsLocalQueryAndRejectsExternalVectors` executes the validator and statically confirms `router.replace(nextPath)` exists. | No test performs a login from the expiry page and observes the resulting route/query. The existing live narrative has no linked screenshot, trace, video, or machine-readable run artifact. |
| AUTH-003-04 | PASS | `returnPathValidatorAcceptsLocalQueryAndRejectsExternalVectors` directly executes the validator against absolute, protocol-relative, encoded external, encoded backslash, and script-like values and asserts `/datascope`; a local path/query is preserved. | Complete for the specified vectors plus the recorded DEF-0004 backslash hardening. |
| AUTH-003-05 | PASS | `unsavedGuardInstallsPromptAndIsWiredToBothDraftWorkspaces` directly invokes the `beforeunload` handler, verifies prevention/return value and cleanup, verifies DataScope and Synthetic wiring, and checks dirty-state refetch guards. | Proves the accepted warning path (rather than restore) and the no-silent-refetch requirement at component level. |
| AUTH-003-06 | BLOCKED | The frontend behavior test proves auth requests and the login route do not trigger another redirect; the filter test proves one login-handler 401 passes through with JSON and no `Location` header. | No executable test asserts that `/login` renders exactly one actionable authentication error or survives a refresh. |
| AUTH-003-07 | PASS | `expiredSessionReturnsStructured401WithoutRedirectOrProtectedHandlerExecution` directly asserts status 401, JSON content type/body, no `Location`, zero protected-handler calls, and a correlation ID. | Complete at filter/API contract level. |
| AUTH-003-08 | BLOCKED | Existing evidence contains only a prose statement about a prior browser run and app-shell source references. | No retained browser trace/screenshot/test proves Back behavior and stale-cache protection. There is no Playwright history/cache test. |

Summary: `4 PASS / 0 FAIL / 4 BLOCKED`.

## Missing evidence and gates

1. A Playwright test that produces one navigation and zero duplicate error notifications under five concurrent 401 responses.
2. A browser test that submits the real login form from an expiry redirect and proves the full original route and query are restored.
3. A browser test that proves one actionable login error is shown and refresh does not recurse.
4. A browser history/cache test that proves Back cannot reveal protected content after reauthentication/logout state changes.
5. A retained trace, screenshot, or machine-readable artifact for any claimed live browser execution.
6. A green `npm.cmd run typecheck` result.
7. A production frontend build from a normal checkout (the isolated dependency junction made this lane's build result inconclusive).
8. Independent reviewer sign-off after the missing browser evidence is captured.

## Defect observed outside AUTH-003 behavior

### Frontend typecheck fails in catalog value-set mapping

Reproduction:

```powershell
cd frontend
npm.cmd run typecheck
```

Observed: `TS2322` at `frontend/src/features/catalog/hooks.ts:67` because the conditional `meta` expression is inferred as `{ Values: string } | { Values?: undefined }`, which is not assignable to `CatalogItem.meta: Record<string, string>`.

Minimal proposed fix scope (not applied):

- `frontend/src/features/catalog/hooks.ts` only
- Give the value-set mapper an explicit `CatalogItem` return type or assign the conditional value to a `Record<string, string>` variable before returning it, so the empty branch is typed as a valid empty record.

No shared defect register or production file was changed in this lane.

## Green decision

`AUTH-003 MUST NOT be marked green yet.`

The implemented redirect, unsafe-next rejection, draft warning, and direct API 401 behaviors have useful passing executable coverage. However, four acceptance cases lack complete direct proof, the required Playwright suite does not exist, and the frontend typecheck exit gate is red. The current story/evidence claim of `8/8 executed live` is therefore stronger than the retained evidence supports.
