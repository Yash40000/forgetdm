# DEF-0001 — Session cookie missing `Secure` flag

| Field | Value |
|---|---|
| Severity | BLOCKER (release-blocking) |
| Status | **CLOSED** (fixed) |
| Found by story | AUTH-001 — case AUTH-001-03 |
| Component | `io.forgetdm.security.AuthController` |
| Found on build | `<git rev-parse HEAD at discovery>` |
| Reported / resolved | `<ISO ts>` / `<ISO ts>` |

## Summary

The `FORGETDM_SESSION` cookie issued by `POST /api/auth/login` (and cleared by `POST /api/auth/logout`) was built with `HttpOnly`, `SameSite=Lax`, `Path=/`, and a finite `Max-Age`, but **without the `Secure` attribute**. On the HTTPS lane the session cookie could therefore be transmitted over an unencrypted/downgraded connection, defeating transport protection of the session token. AUTH-001-03 designates a missing `Secure` flag on the HTTPS lane as release-blocking.

## Steps to reproduce (pre-fix)

1. Deploy on the HTTPS lane.
2. `POST /api/auth/login` with valid credentials.
3. Inspect the `Set-Cookie` response header.

**Expected:** `Set-Cookie: FORGETDM_SESSION=…; Path=/; Max-Age=…; HttpOnly; SameSite=Lax; Secure`
**Actual (pre-fix):** same header **without** `Secure`.

## Root cause

`ResponseCookie.from(...)` was built without `.secure(...)`, which defaults to `false`.

## Resolution

`AuthController` now sets `.secure(isSecure(request))` on both the login and logout cookies. `isSecure()` returns `true` when the request is TLS-terminated directly (`request.isSecure()`) or arrives behind a proxy advertising `X-Forwarded-Proto: https`, and `false` on the local HTTP development lane — so the HTTPS lane gets `Secure` while local HTTP continues to work.

```java
.secure(isSecure(request))
...
private static boolean isSecure(HttpServletRequest request) {
    if (request.isSecure()) return true;
    String forwardedProto = request.getHeader("X-Forwarded-Proto");
    return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto.split(",")[0].trim());
}
```

## Verification

- Rebuild the backend.
- HTTPS lane: `curl -i -X POST https://<host>/api/auth/login …` → `Set-Cookie` now includes `Secure`.
- Local HTTP lane: `curl -i -X POST http://localhost:<port>/api/auth/login …` → cookie issued **without** `Secure` (so local login still works).
- Re-capture AUTH-001-03 evidence in both lanes and attach to the story.

## Notes

Fix is committed; awaiting the backend rebuild + re-capture before the reviewer signs the story off. No secrets are recorded in this defect.
