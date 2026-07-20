# ForgeTDM Product Readiness Story Board

ForgeTDM product validation is managed as a continuous queue, not by sprint or release-cycle milestones.

## Board Views

- [All active test stories](https://github.com/Yash40000/forgetdm/issues?q=is%3Aissue%20is%3Aopen%20label%3Atype%3Atest-story)
- [Ready to start - maximum 10](https://github.com/Yash40000/forgetdm/issues?q=is%3Aissue%20is%3Aopen%20label%3Atype%3Atest-story%20label%3Astatus%3Aready)
- [In progress](https://github.com/Yash40000/forgetdm/issues?q=is%3Aissue%20is%3Aopen%20label%3Atype%3Atest-story%20label%3Astatus%3Ain-progress)
- [Blocked](https://github.com/Yash40000/forgetdm/issues?q=is%3Aissue%20is%3Aopen%20label%3Atype%3Atest-story%20label%3Astatus%3Ablocked)
- [Backlog](https://github.com/Yash40000/forgetdm/issues?q=is%3Aissue%20is%3Aopen%20label%3Atype%3Atest-story%20label%3Astatus%3Abacklog)
- [Completed with evidence](https://github.com/Yash40000/forgetdm/issues?q=is%3Aissue%20label%3Atype%3Atest-story%20label%3Astatus%3Adone)

## Queue Policy

1. Keep no more than ten stories in `status:ready`.
2. Treat `status:ready` as queue eligibility only. Execution starts only after `STORY_EXECUTION_APPROVALS.json` records the approver and allowed scope and `assert-story-approved.ps1` passes.
3. Move an approved selected story from `status:ready` to `status:in-progress` when execution starts.
4. Move a failed story to `status:blocked` and link the defect, sanitized evidence, and affected build.
5. Move a passing story to `status:done` only after regression and evidence review, then close it.
6. Promote the next highest-risk dependency-ready story from `status:backlog` to `status:ready` so the Ready queue returns to ten.
7. Never use a sprint date to imply release readiness. Readiness comes from passed gates and reviewed evidence.

## Source of Truth

- Master plan: [FORGETDM_MASTER_TEST_PLAN.md](FORGETDM_MASTER_TEST_PLAN.md)
- 212-family catalog: [FORGETDM_TEST_CASE_CATALOG.csv](FORGETDM_TEST_CASE_CATALOG.csv)
- Current Ready test pack: [cases/ready/README.md](cases/ready/README.md)
- Repeatable GitHub sync: [sync-testing-stories.ps1](../../scripts/github/sync-testing-stories.ps1)

The GitHub credential currently has repository access but not GitHub Projects permission. These filtered Issues views therefore serve as the working board without weakening repository privacy or requiring a paid test-management product.
