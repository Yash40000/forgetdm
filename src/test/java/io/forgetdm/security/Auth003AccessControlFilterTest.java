package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Auth003AccessControlFilterTest {

    @Test
    void expiredSessionReturnsStructured401WithoutRedirectOrProtectedHandlerExecution() throws Exception {
        AccessControlService access = mock(AccessControlService.class);
        AuditService audit = mock(AuditService.class);
        when(access.principalFromRequest(any())).thenReturn(Optional.empty());
        AccessControlFilter filter = new AccessControlFilter(access, audit);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/policies");
        request.setCookies(new Cookie(AccessControlService.SESSION_COOKIE, "expired-session-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger protectedHandlerCalls = new AtomicInteger();
        FilterChain protectedHandler = (req, res) -> protectedHandlerCalls.incrementAndGet();

        filter.doFilter(request, response, protectedHandler);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("{\"error\":\"Login required\"}", response.getContentAsString());
        assertNull(response.getHeader("Location"), "API authentication failures must never issue an HTTP redirect");
        assertEquals(0, protectedHandlerCalls.get(), "expired callers must not reach the protected endpoint");
        assertTrue(response.getHeader(AccessControlFilter.CORRELATION_ID_HEADER).length() >= 8);
        verify(access).principalFromRequest(request);
        verifyNoInteractions(audit);
    }

    @Test
    void loginEndpoint401PassesThroughOnceWithoutFilterRedirectLoop() throws Exception {
        AccessControlService access = mock(AccessControlService.class);
        AuditService audit = mock(AuditService.class);
        AccessControlFilter filter = new AccessControlFilter(access, audit);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger loginHandlerCalls = new AtomicInteger();

        filter.doFilter(request, response, (req, res) -> {
            loginHandlerCalls.incrementAndGet();
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            http.setContentType("application/json");
            http.getWriter().write("{\"error\":\"Invalid username or password\"}");
        });

        assertEquals(1, loginHandlerCalls.get());
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertEquals("{\"error\":\"Invalid username or password\"}", response.getContentAsString());
        assertNull(response.getHeader("Location"));
        verifyNoInteractions(access, audit);
    }
}
