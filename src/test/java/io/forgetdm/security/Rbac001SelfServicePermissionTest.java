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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Rbac001SelfServicePermissionTest {

    @Test
    void catalogAdministrationRequiresDatascopeManageRatherThanProvisionRun() throws Exception {
        assertDenied("POST", "/api/self-service/v2/products", Set.of("provision.run"));
        assertDenied("POST", "/api/self-service/v2/products/product-1/disable", Set.of("provision.run"));
        assertDenied("GET", "/api/self-service/v2/products", Set.of("provision.read"));
        assertDenied("GET", "/api/self-service/v2/candidates", Set.of("provision.read"));

        assertAllowed("POST", "/api/self-service/v2/products", Set.of("datascope.manage"));
        assertAllowed("GET", "/api/self-service/v2/products", Set.of("datascope.manage"));
        assertAllowed("GET", "/api/self-service/v2/candidates", Set.of("datascope.manage"));
    }

    @Test
    void requestAndApprovalRoutesRetainTheirDedicatedPermissions() throws Exception {
        assertAllowed("POST", "/api/self-service/v2/orders", Set.of("provision.run"));
        assertDenied("POST", "/api/self-service/v2/orders/order-1/decision/approve", Set.of("provision.run"));
        assertAllowed("POST", "/api/self-service/v2/orders/order-1/decision/approve", Set.of("provision.approve"));
    }

    private static void assertAllowed(String method, String path, Set<String> permissions) throws Exception {
        Result result = execute(method, path, permissions);
        assertEquals(1, result.chainCalls(), method + " " + path + " should reach the controller");
        assertEquals(200, result.response().getStatus());
    }

    private static void assertDenied(String method, String path, Set<String> permissions) throws Exception {
        Result result = execute(method, path, permissions);
        assertEquals(0, result.chainCalls(), method + " " + path + " must be stopped by the permission filter");
        assertEquals(403, result.response().getStatus());
    }

    private static Result execute(String method, String path, Set<String> permissions) throws Exception {
        AccessControlService access = mock(AccessControlService.class);
        AuditService audit = mock(AuditService.class);
        AccessPrincipal principal = new AccessPrincipal(42L, "rbac-user", "RBAC User", Set.of(), permissions);
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
