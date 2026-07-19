# DEF-0027 - Browser Back briefly renders protected content after logout

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED - browser retest and independent trace review passed |
| Found by story | AUTH-003 - case AUTH-003-08 independent trace review |
| Component | `frontend/src/components/app-shell.tsx` |
| Found | 2026-07-19 |

## Problem

After sign-out, browser Back could restore the protected Synthetic route and render its children while the shell's `/api/auth/me` query was still pending. The retained trace showed the protected workspace for several seconds before the eventual login redirect. The previous test asserted only the eventual URL and therefore missed the transient exposure.

## Fix

The application shell now renders only a neutral session-verification state until `/api/auth/me` positively identifies an authenticated user. Unauthenticated, pending, and failed checks never place protected children in the rendered tree.

## Verification

AUTH-003-08 arms a cross-navigation exposure observer before Back. The focused run and full suite both passed. Independent trace review confirmed that only `Verifying session` appeared while authentication resolved, the protected Synthetic heading never rendered, and the exposure marker remained zero.
