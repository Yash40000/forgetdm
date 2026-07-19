# Defect Register

Defects found while executing the ForgeTDM test stories. Each defect is a tracked record here and should be mirrored to a GitHub issue labeled `type:defect` and linked to the story that surfaced it.

## Rules

- A defect is raised for every reproducible deviation from a story's acceptance criteria.
- If the defect is fixed in the same pass, record the fix (file + commit) and set **Status: CLOSED** with a verification note.
- If not fixed, leave **Status: OPEN**, set severity, and link it from the story so the story moves to `status:blocked` when the failing case is required.
- Never record credentials, tokens, cookie values, clear PII, or secrets.

## Severity

`BLOCKER` (release-blocking) · `HIGH` · `MEDIUM` · `LOW`

## Register

| ID | Title | Severity | Status | Story | Fix |
|---|---|---|---|---|---|
| [DEF-0001](DEF-0001-session-cookie-missing-secure.md) | Session cookie missing `Secure` flag | BLOCKER | CLOSED | AUTH-001 (AUTH-001-03) | `AuthController` — `.secure(isSecure(request))` |
| [DEF-0002](DEF-0002-me-omits-group-membership.md) | `/api/auth/me` omits group membership | LOW | CLOSED | AUTH-001 (AUTH-001-02) | `AccessPrincipal.groups` populated in `principal()` (fixed with DEF-0007) — verified live |
| [DEF-0003](DEF-0003-expiry-redirect-destroys-unsaved-draft.md) | Expiry redirect silently destroys unsaved draft | MEDIUM | CLOSED | AUTH-003 (AUTH-003-05) | `useUnsavedGuard` wired into DataScope + Synthetic |
| [DEF-0004](DEF-0004-next-open-redirect-backslash-bypass.md) | `next` open-redirect backslash bypass | LOW | CLOSED | AUTH-003 (AUTH-003-04) | `safeNextPath` origin + backslash checks |
| [DEF-0005](DEF-0005-ui-does-not-gate-actions-by-permission.md) | UI does not gate actions/nav by permission | MEDIUM | CLOSED | RBAC-001 (RBAC-001-04) | `usePermissions`/`<Can>` layer + nav gating + 6 core pages gated (verified live) |
| [DEF-0006](DEF-0006-extend-permission-gating-remaining-pages.md) | Extend permission gating to remaining pages | LOW | OPEN | RBAC-001 (RBAC-001-04) | — |
| [DEF-0007](DEF-0007-no-cross-group-object-isolation.md) | No cross-group/tenant isolation for core objects | HIGH (S1) | CLOSED | RBAC-002 (01/02/03/06) | V61 tenancy migration + `OwnershipGuard` + scoping in policy/datasource/dataset/reservation services — verified live |
| [DEF-0008](DEF-0008-failed-login-audit-rolled-back.md) | Failed-login audit rolled back — no failed auth ever recorded | HIGH | CLOSED | AUD-001 (AUD-001-05) | `AuditWriter` with `REQUIRES_NEW` — verified live (0 → 1 `LOGIN_FAILED`) |
| [DEF-0009](DEF-0009-audit-chain-forked-and-verify-aborts.md) | Audit hash chain forked; verify aborts leaving ~3,000 events unverified | HIGH | CLOSED | AUD-001 (AUD-001-08) | V62 sequence integrity + non-aborting `verifyChain` — verified live (11 → 2,345 verified) |
| [DEF-0010](DEF-0010-audit-events-lack-resource-identity.md) | Audit events lack resource identity / correlation context | MEDIUM | OPEN (partial fix) | AUD-001 (AUD-001-01) | Resource identity on policy/ACCESS_DENIED/export verified live; remaining ~90 actions pending |
| [DEF-0011](DEF-0011-audit-csv-silent-truncation.md) | Audit CSV export silently truncates at 5,000 | MEDIUM | CLOSED | AUD-001 (AUD-001-07) | Truncation headers + `# TRUNCATED` row — verified live |
| [DEF-0012](DEF-0012-audit-export-not-audited.md) | Exporting the audit trail is not itself audited | MEDIUM | CLOSED | AUD-001 (AUD-001-04) | `AUDIT_EXPORTED` before delivery — verified live |
| [DEF-0013](DEF-0013-audit-records-raw-jdbc-urls.md) | Audit detail records raw JDBC URLs (credential-leak risk) | LOW | CLOSED | AUD-001 (AUD-001-09) | `safeJdbc()` redaction — verified live with a credential-bearing URL |
| [DEF-0014](DEF-0014-aud-001-false-pass-claim.md) | AUD-001 marked PASSED on evidence testing none of its criteria | MEDIUM (process) | OPEN | AUD-001 (meta) | Status reset + real evidence written; process rule to enforce |
| [DEF-0015](DEF-0015-audit-hash-timestamp-rounding.md) | Audit hash timestamp doesn't round-trip → false tamper alarms | HIGH | CLOSED | AUD-001 (AUD-001-08) | Millisecond truncation before hashing — verified live (45/45 new events clean) |
| [DEF-0016](DEF-0016-login-timing-username-enumeration.md) | Login timing side-channel enables username enumeration | MEDIUM | CLOSED | AUTH-001 (AUTH-001-04) | Constant-work PBKDF2 — verified live (27.5× → 1.00×) |
| [DEF-0017](DEF-0017-datasource-create-has-no-validation.md) | Data-source create/update accepts invalid input and persists it | MEDIUM | CLOSED | DSRC-001 (DSRC-001-02) | `validate()` on create+update — verified live (6/6 rejected, 11→11 rows) |
| [DEF-0018](DEF-0018-raw-db-exception-leaked-to-clients.md) | Raw DB exceptions (SQL, schema, row values) returned to clients | MEDIUM | CLOSED | DSRC-001 (DSRC-001-02) | Sanitising `GlobalExceptionHandler` — verified live (zero leaks, app-wide) |
| [DEF-0019](DEF-0019-datasource-dependency-and-test-audit-gaps.md) | Blocked delete names no dependencies; connection tests unaudited | MEDIUM | CLOSED | DSRC-001 (06, 08) | 409 naming all 6 blockers; `DATASOURCE_TESTED` recorded — verified live |
| [DEF-0020](DEF-0020-connection-failures-not-classified.md) | Connection failures unclassified; DNS and timeout indistinguishable | MEDIUM | FIX WRITTEN | DSRC-002 (01, 03, 04, 05) | SQLState-first `classify()` → `[AUTH]/[DNS]/[TIMEOUT]/[TLS]/…` + `elapsedMs` |

**FIX WRITTEN** = code committed but not yet verified live — requires a backend rebuild.

| [DEF-0021](DEF-0021-unstructured-tika-commons-lang-mismatch.md) | Tika runtime mismatch failed unstructured masking before cancellation | HIGH | CLOSED | AUD-001 (02) | Commons Lang 3.20.0 pin + pre-detection cancellation guard; live lifecycle 27/27 |
| [DEF-0022](DEF-0022-business-entity-governance-audit-attribution.md) | Business-entity governance audit attribution was incomplete | HIGH | FIX WRITTEN | AUD-001 (03, 09) | Structured governance/package audit and approved-reviewer validation; live proof pending |
| [DEF-0023](DEF-0023-catalog-value-set-typecheck.md) | Catalog value-set metadata typing broke the frontend type gate | LOW | CLOSED | AUTH-003 exit gate | Explicit metadata typing; frontend typecheck green |
| [DEF-0024](DEF-0024-datasource-stale-update-and-role-capability.md) | Data-source stale updates and role misuse were not blocked | HIGH | CLOSED | DSRC-001 (04, 07) | V64 optimistic locking + source/target capability preflight; PostgreSQL, Oracle, and MySQL live proof retained |
| [DEF-0025](DEF-0025-db2-zos-native-loader-misroute.md) | DB2 z/OS could be routed to the DB2 LUW native LOAD adapter | HIGH | CLOSED | DSRC-001 connector contract | DB2 z/OS now uses JDBC fallback unless a site-specific adapter exists; focused contract tests green |
| [DEF-0026](DEF-0026-cancelled-auth-redirect-latch.md) | Canceled expiry navigation left the redirect latch permanently set | MEDIUM | CLOSED | AUTH-003 (AUTH-003-05) | Bounded latch reset; two consecutive dirty-draft expiry attempts pass in Edge |
| [DEF-0027](DEF-0027-protected-content-flash-after-logout-back.md) | Browser Back briefly rendered protected content after logout | HIGH | CLOSED | AUTH-003 (AUTH-003-08) | Gate shell children until positive authentication; hardened browser trace independently accepted |
| [DEF-0028](DEF-0028-auth-notification-counter-navigation-reset.md) | AUTH notification counter reset during login navigation | MEDIUM (test) | CLOSED | AUTH-003 (AUTH-003-02) | Persist counter across same-origin documents; hardened trace independently accepted |

## Mirror to GitHub

```bash
# DEF-0001 (fixed → open then close with the fix commit)
gh issue create --repo Yash40000/forgetdm \
  --title "DEF-0001 — Session cookie missing Secure flag (AUTH-001-03)" \
  --label "type:defect" --label "severity:blocker" \
  --body-file docs/testing/defects/DEF-0001-session-cookie-missing-secure.md
gh issue close <number> --comment "Fixed in <commit>: session cookie now Secure on the HTTPS lane. Re-captured AUTH-001-03 in both lanes."

# DEF-0002 (open)
gh issue create --repo Yash40000/forgetdm \
  --title "DEF-0002 — /api/auth/me omits group membership (AUTH-001-02)" \
  --label "type:defect" --label "severity:low" \
  --body-file docs/testing/defects/DEF-0002-me-omits-group-membership.md
```
