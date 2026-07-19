# DEF-0022 - Business-entity governance audit attribution was incomplete

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | **FIX WRITTEN - focused tests pass; live verification pending** |
| Found by story | AUD-001-03, AUD-001-09 |
| Component | Business-entity governance, package versioning, and promotion |
| Found | 2026-07-18 controller audit coverage review |

## Impact

Governance request, approval, rejection, immutable-version, and promotion actions relied on legacy
free-form audit calls. The events did not consistently prove the real maker/checker, governed
resource, decision, version, outcome, or safe decision metadata. Promotion also accepted a supplied
approver value without proving it matched the approved reviewer.

This prevented AUD-001 from proving maker-checker attribution and left room for misleading approval
metadata even though the underlying governance state checks remained in place.

## Fix

- Emit structured audit records for governance request, approval, rejection, immutable-version, and
  promotion actions.
- Record the authenticated actor plus governed resource identity, decision, risk, reviewer, version,
  environment, and explicit outcome.
- Keep comments and signatures out of audit metadata while retaining safe evidence identifiers.
- Reject promotion when a supplied approver does not match the reviewer who approved the request.

## Verification

- `BusinessEntityEnterpriseServiceTest`: 9 tests passed, 0 failed, 0 errors, 0 skipped.
- Added `governanceAndPackageAuditsAreStructuredAttributedAndSecretFree`.
- The focused test rejects a promotion that names an approver other than the approved governance
  signer, then proves the correctly attributed promotion succeeds.
- Added `docs/testing/run-aud-001-governance.ps1` for retained live maker/checker evidence.
- Live verification remains pending because no rebuilt backend was available in this execution lane.
