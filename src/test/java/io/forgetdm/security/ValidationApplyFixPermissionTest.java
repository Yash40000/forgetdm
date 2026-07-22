package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValidationApplyFixPermissionTest {

    @Test
    void applyFixRequiresValidationRunAndPolicyManage() throws Exception {
        Result validationOnly = execute("POST", "/api/validation/apply-fix", Set.of("validation.run"));
        assertEquals(403, validationOnly.response().getStatus());
        assertEquals(0, validationOnly.chainCalls());
        assertTrue(validationOnly.response().getContentAsString().contains("policy.manage"));

        Result policyOnly = execute("POST", "/api/validation/apply-fix", Set.of("policy.manage"));
        assertEquals(403, policyOnly.response().getStatus());
        assertEquals(0, policyOnly.chainCalls());
        assertTrue(policyOnly.response().getContentAsString().contains("validation.run"));

        Result both = execute("POST", "/api/validation/apply-fix", Set.of("validation.run", "policy.manage"));
        assertEquals(200, both.response().getStatus());
        assertEquals(1, both.chainCalls());
    }

    @Test
    void ordinaryValidationRunKeepsItsSingleValidationPermission() throws Exception {
        Result result = execute("POST", "/api/validation/run", Set.of("validation.run"));
        assertEquals(200, result.response().getStatus());
        assertEquals(1, result.chainCalls());
    }

    private static Result execute(String method, String path, Set<String> permissions) throws Exception {
        AccessControlService access = mock(AccessControlService.class);
        AuditService audit = mock(AuditService.class);
        AccessPrincipal principal = new AccessPrincipal(42L, "validator", "Validator", Set.of(), permissions);
        when(access.principalFromRequest(any())).thenReturn(Optional.of(principal));
        when(access.tokenFromRequest(any())).thenReturn(Optional.empty());

        AccessControlFilter filter = new AccessControlFilter(access, audit);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (req, res) -> calls.incrementAndGet();

        filter.doFilter(request, response, chain);
        return new Result(response, calls.get());
    }

    private record Result(MockHttpServletResponse response, int chainCalls) {}
}
