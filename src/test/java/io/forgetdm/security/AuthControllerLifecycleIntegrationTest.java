package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({AccessControlFilter.class, AuthControllerLifecycleIntegrationTest.ProtectedController.class})
class AuthControllerLifecycleIntegrationTest {
    private static final String TOKEN = "auth001-integration-session";
    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AccessControlService access;

    @MockBean
    private AuditService audit;

    private final AtomicBoolean revoked = new AtomicBoolean();
    private AccessPrincipal principal;

    @BeforeEach
    void setUpLifecycle() {
        revoked.set(false);
        principal = new AccessPrincipal(
                41L,
                "auth001-user",
                "AUTH-001 User",
                Set.of("TDM_ARCHITECT"),
                Set.of("dashboard.read"),
                List.of(new AccessControlService.GroupLite(7L, "QA"))
        );
        when(access.login("auth001-user", "valid-password"))
                .thenReturn(new AccessControlService.LoginResult(TOKEN, EXPIRES_AT, principal));
        when(access.principalFromRequest(any())).thenAnswer(invocation ->
                revoked.get() ? Optional.empty() : Optional.of(principal));
        when(access.tokenFromRequest(any())).thenReturn(Optional.of(TOKEN));
        doAnswer(invocation -> {
            revoked.set(true);
            return null;
        }).when(access).logout(TOKEN);
    }

    @Test
    void loginMeLogoutAndOldCookieReplayUseOneSpringMvcLifecycle() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .secure(false)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth001-user\",\"password\":\"valid-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.username").value("auth001-user"))
                .andExpect(jsonPath("$.user.groups[0].name").value("QA"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(cookie().value(AccessControlService.SESSION_COOKIE, TOKEN))
                .andExpect(cookie().httpOnly(AccessControlService.SESSION_COOKIE, true))
                .andExpect(cookie().secure(AccessControlService.SESSION_COOKIE, false))
                .andExpect(cookie().path(AccessControlService.SESSION_COOKIE, "/"))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=")));

        mvc.perform(get("/api/auth/me").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.username").value("auth001-user"));

        mvc.perform(get("/api/auth001/protected").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true}"));

        mvc.perform(post("/api/auth/logout").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(cookie().maxAge(AccessControlService.SESSION_COOKIE, 0))
                .andExpect(cookie().httpOnly(AccessControlService.SESSION_COOKIE, true))
                .andExpect(cookie().path(AccessControlService.SESSION_COOKIE, "/"))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));

        verify(access).logout(TOKEN);

        mvc.perform(get("/api/auth001/protected").cookie(sessionCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"Login required\"}"));

        mvc.perform(get("/api/auth/me").cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void secureRequestMarksLoginAndLogoutCookiesSecure() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth001-user\",\"password\":\"valid-password\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().secure(AccessControlService.SESSION_COOKIE, true));

        mvc.perform(post("/api/auth/logout").secure(true).cookie(sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(cookie().secure(AccessControlService.SESSION_COOKIE, true))
                .andExpect(cookie().maxAge(AccessControlService.SESSION_COOKIE, 0));
    }

    private static jakarta.servlet.http.Cookie sessionCookie() {
        return new jakarta.servlet.http.Cookie(AccessControlService.SESSION_COOKIE, TOKEN);
    }

    @RestController
    public static class ProtectedController {
        @GetMapping("/api/auth001/protected")
        java.util.Map<String, Boolean> protectedEndpoint() {
            return java.util.Map.of("ok", true);
        }
    }
}
