# DEF-0028 - AUTH notification counter reset during login navigation

| Field | Value |
|---|---|
| Severity | MEDIUM (test evidence) |
| Status | CLOSED - browser retest and independent trace review passed |
| Found by story | AUTH-003 - case AUTH-003-02 independent evidence review |
| Component | `frontend/e2e/auth-003/expired-session.spec.ts` |
| Found | 2026-07-19 |

## Problem

The browser test initialized `auth003.maxNotifications` to zero in an init script that runs for every document. Navigation from the expired page to login therefore reset the value before the assertion read it. A reported zero described the login document, not necessarily the page handling five concurrent 401 responses.

## Fix

The counter now initializes only when its session-storage key is absent. Its maximum value survives the same-origin login navigation, so the post-navigation assertion reflects notifications observed on both documents.

## Verification

AUTH-003-02 passed alone and in the full suite with at least five distinct expired requests, one login document request, and a persistent maximum notification count of zero. Independent trace review confirmed the value came from the expired-page flow and survived login navigation.
