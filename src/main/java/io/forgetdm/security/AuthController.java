package io.forgetdm.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccessControlService access;

    public AuthController(AccessControlService access) {
        this.access = access;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        AccessControlService.LoginResult result = access.login(req.username(), req.password());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AccessControlService.SESSION_COOKIE, result.token())
                .httpOnly(true)
                .secure(isSecure(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.between(java.time.Instant.now(), result.expiresAt()))
                .build()
                .toString());
        return Map.of("authenticated", true, "user", result.principal(), "expiresAt", result.expiresAt());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        access.principalFromRequest(request);
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (AccessControlService.SESSION_COOKIE.equals(cookie.getName())) {
                    access.logout(cookie.getValue());
                    break;
                }
            }
        }
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AccessControlService.SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(isSecure(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build()
                .toString());
        return Map.of("authenticated", false);
    }

    /** Mark the session cookie Secure on the HTTPS lane (directly TLS-terminated or behind a proxy that sets
     *  X-Forwarded-Proto), while leaving it usable on the local HTTP development lane. */
    private static boolean isSecure(HttpServletRequest request) {
        if (request.isSecure()) return true;
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto.split(",")[0].trim());
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        return access.principalFromRequest(request)
                .<Map<String, Object>>map(p -> Map.of("authenticated", true, "user", p))
                .orElseGet(() -> Map.of("authenticated", false));
    }

    public record LoginRequest(String username, String password) {}
}
