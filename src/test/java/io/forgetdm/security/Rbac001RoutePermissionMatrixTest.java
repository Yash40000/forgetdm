package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Rbac001RoutePermissionMatrixTest {

    private static final Set<String> KNOWN_PERMISSIONS = Set.of(
            "admin.all", "security.admin", "dashboard.read",
            "datasource.read", "datasource.manage", "discovery.read", "discovery.manage",
            "policy.read", "policy.manage", "datascope.read", "datascope.manage",
            "provision.read", "provision.run", "provision.approve",
            "synthetic.read", "synthetic.profile", "synthetic.manage", "synthetic.run",
            "synthetic.direct.run", "synthetic.approve", "synthetic.cancel", "synthetic.export",
            "ri.read", "ri.manage", "mapping.read", "mapping.manage", "mapping.run",
            "unstructured.read", "unstructured.manage", "unstructured.run", "unstructured.cancel",
            "query.run", "validation.read", "validation.run", "reservation.read", "reservation.manage",
            "virtualization.read", "virtualization.manage", "mainframe.read", "mainframe.manage",
            "assistant.use", "assistant.manage", "audit.read", "integration.read", "integration.manage"
    );

    private static final Map<String, Set<String>> EXPECTED_ROLE_PERMISSIONS = Map.of(
            "ADMIN", Set.of("admin.all"),
            "TDM_ARCHITECT", Set.of(
                    "dashboard.read", "datasource.read", "datasource.manage", "discovery.read", "discovery.manage",
                    "policy.read", "policy.manage", "datascope.read", "datascope.manage", "provision.read",
                    "provision.run", "provision.approve", "synthetic.read", "synthetic.profile", "synthetic.manage",
                    "synthetic.run", "synthetic.direct.run", "synthetic.approve", "synthetic.cancel", "synthetic.export",
                    "ri.read", "ri.manage", "mapping.read", "mapping.manage", "mapping.run", "unstructured.read",
                    "unstructured.manage", "unstructured.run", "unstructured.cancel", "query.run", "validation.read",
                    "validation.run", "reservation.read", "reservation.manage", "virtualization.read",
                    "virtualization.manage", "mainframe.read", "mainframe.manage", "assistant.use", "assistant.manage",
                    "audit.read", "integration.read", "integration.manage"),
            "DATA_ENGINEER", Set.of(
                    "dashboard.read", "datasource.read", "discovery.read", "policy.read", "datascope.read",
                    "datascope.manage", "provision.read", "provision.run", "synthetic.read", "synthetic.profile",
                    "synthetic.manage", "synthetic.run", "synthetic.direct.run", "synthetic.cancel", "synthetic.export",
                    "ri.read", "ri.manage", "mapping.read", "mapping.manage", "mapping.run", "unstructured.read",
                    "unstructured.manage", "unstructured.run", "unstructured.cancel", "query.run", "validation.read",
                    "validation.run", "reservation.read", "virtualization.read", "mainframe.read", "assistant.use"),
            "TESTER", Set.of(
                    "dashboard.read", "datasource.read", "policy.read", "datascope.read", "provision.read",
                    "synthetic.read", "synthetic.run", "synthetic.export", "ri.read", "ri.manage", "unstructured.read",
                    "unstructured.run", "unstructured.cancel", "reservation.read", "reservation.manage",
                    "validation.read", "query.run", "assistant.use"),
            "AUDITOR", Set.of(
                    "dashboard.read", "datasource.read", "discovery.read", "policy.read", "datascope.read",
                    "provision.read", "synthetic.read", "mapping.read", "unstructured.read", "ri.read",
                    "validation.read", "reservation.read", "virtualization.read", "mainframe.read", "audit.read",
                    "integration.read")
    );

    @ParameterizedTest(name = "{0} {1} requires {2}")
    @MethodSource("protectedRoutes")
    void eachProtectedRouteRequiresItsExactPermission(String method, String path, String permission) throws Exception {
        Result allowed = execute(method, path, Set.of(permission));
        assertEquals(1, allowed.chainCalls(), "exact permission should reach the controller");
        assertEquals(200, allowed.response().getStatus());

        Result denied = execute(method, path, Set.of("irrelevant.permission"));
        assertEquals(0, denied.chainCalls(), "unrelated permission must not reach the controller");
        assertEquals(403, denied.response().getStatus());
        assertTrue(denied.response().getContentAsString().contains(permission));
        verify(denied.audit()).record(any(), eq("ACCESS_DENIED"), any(), any(), any(), any(), any(), any(), any());

        Result admin = execute(method, path, Set.of("admin.all"));
        assertEquals(1, admin.chainCalls(), "admin.all must remain the documented wildcard");
        assertEquals(200, admin.response().getStatus());
    }

    @ParameterizedTest(name = "{0} {1} is authenticated self-service")
    @MethodSource("authenticatedRoutesWithoutFeaturePermission")
    void authenticatedRoutesWithoutFeaturePermissionRemainAuthenticatedOnly(String method, String path) throws Exception {
        Result result = execute(method, path, Set.of());
        assertEquals(1, result.chainCalls());
        assertEquals(200, result.response().getStatus());
    }

    @Test
    void builtInRoleDefinitionsContainOnlyDocumentedPermissions() {
        Set<String> names = new HashSet<>();
        for (RoleDefinition role : RoleDefinition.ALL) {
            assertTrue(names.add(role.name()), "duplicate role name " + role.name());
            assertFalse(role.permissions().isEmpty(), role.name() + " must define an explicit permission set");
            assertTrue(KNOWN_PERMISSIONS.containsAll(role.permissions()),
                    () -> role.name() + " contains unknown permissions " + difference(role.permissions(), KNOWN_PERMISSIONS));
        }
        assertEquals(Set.of("ADMIN", "TDM_ARCHITECT", "DATA_ENGINEER", "TESTER", "AUDITOR"), names);
        Map<String, Set<String>> actual = RoleDefinition.ALL.stream()
                .collect(java.util.stream.Collectors.toMap(RoleDefinition::name, RoleDefinition::permissions));
        assertEquals(EXPECTED_ROLE_PERMISSIONS, actual, "built-in role permissions are a versioned RBAC contract");
    }

    private static Stream<Arguments> protectedRoutes() {
        return Stream.of(
                route("GET", "/api/security/users", "security.admin"),
                route("GET", "/api/integrations", "integration.read"),
                route("POST", "/api/integrations", "integration.manage"),
                route("GET", "/api/self-service/v2/products", "datascope.manage"),
                route("GET", "/api/self-service/v2/candidates", "datascope.manage"),
                route("POST", "/api/self-service/v2/templates/42", "datascope.manage"),
                route("GET", "/api/self-service/v2/orders", "provision.read"),
                route("POST", "/api/self-service/v2/orders", "provision.run"),
                route("POST", "/api/self-service/v2/orders/42/decision/approve", "provision.approve"),
                route("GET", "/api/audit", "audit.read"),
                route("POST", "/api/audit/reanchor", "admin.all"),
                route("GET", "/api/dashboard/summary", "dashboard.read"),
                route("GET", "/api/datasources", "datasource.read"),
                route("POST", "/api/datasources", "datasource.manage"),
                route("POST", "/api/query", "query.run"),
                route("GET", "/api/discovery/jobs", "discovery.read"),
                route("POST", "/api/discovery/scan", "discovery.manage"),
                route("GET", "/api/policies", "policy.read"),
                route("POST", "/api/policies/preview", "policy.read"),
                route("POST", "/api/policies", "policy.manage"),
                route("GET", "/api/ri/relationships", "ri.read"),
                route("POST", "/api/ri/relationships", "ri.manage"),
                route("GET", "/api/datasets", "datascope.read"),
                route("POST", "/api/datasets", "datascope.manage"),
                route("GET", "/api/datascope/blueprints", "datascope.read"),
                route("POST", "/api/datascope/blueprints", "datascope.manage"),
                route("GET", "/api/business-entities", "datascope.read"),
                route("POST", "/api/business-entities", "datascope.manage"),
                route("POST", "/api/business-entities/1/governance-requests/2/approve", "provision.approve"),
                route("POST", "/api/subset/preview", "datascope.manage"),
                route("GET", "/api/jobs", "provision.read"),
                route("POST", "/api/jobs", "provision.run"),
                route("POST", "/api/jobs/42/approval/approve", "provision.approve"),
                route("GET", "/api/synthetic/generators", "synthetic.read"),
                route("POST", "/api/synthetic/preview", "synthetic.read"),
                route("POST", "/api/synthetic/profile", "synthetic.profile"),
                route("POST", "/api/synthetic/saved-jobs", "synthetic.manage"),
                route("POST", "/api/synthetic/saved-jobs/42/run", "synthetic.run"),
                route("POST", "/api/synthetic/jobs/42/cancel", "synthetic.cancel"),
                route("POST", "/api/synthetic/jobs/42/partitions/7/cancel", "synthetic.cancel"),
                route("POST", "/api/synthetic/jobs/42/partitions/7/retry", "synthetic.run"),
                route("POST", "/api/synthetic/saved-jobs/42/approval/approve", "synthetic.approve"),
                route("POST", "/api/synthetic/saved-jobs/42/export", "synthetic.export"),
                route("POST", "/api/synthetic/generate/start", "synthetic.direct.run"),
                route("GET", "/api/mappings", "mapping.read"),
                route("POST", "/api/mappings/validate", "mapping.read"),
                route("POST", "/api/mappings", "mapping.manage"),
                route("POST", "/api/mappings/42/runs", "mapping.run"),
                route("POST", "/api/mappings/runs/42/cancel", "mapping.run"),
                route("POST", "/api/mappings/runs/42/retry", "mapping.run"),
                route("POST", "/api/mappings/workflows/42/run", "mapping.run"),
                route("POST", "/api/mappings/load", "mapping.run"),
                route("POST", "/api/mappings/load-multi", "mapping.run"),
                route("GET", "/api/unstructured/jobs", "unstructured.read"),
                route("POST", "/api/unstructured/preview", "unstructured.read"),
                route("POST", "/api/unstructured/profiles", "unstructured.manage"),
                route("POST", "/api/unstructured/jobs", "unstructured.run"),
                route("POST", "/api/unstructured/jobs/42/cancel", "unstructured.cancel"),
                route("GET", "/api/reservations", "reservation.read"),
                route("POST", "/api/reservations", "reservation.manage"),
                route("GET", "/api/validation/suites", "validation.read"),
                route("POST", "/api/validation/runs", "validation.run"),
                route("GET", "/api/virtualization/databases", "virtualization.read"),
                route("POST", "/api/virtualization/databases", "virtualization.manage"),
                route("GET", "/api/mainframe/copybooks", "mainframe.read"),
                route("POST", "/api/copybook/parse", "mainframe.manage"),
                route("POST", "/api/mainframe/jobs", "mainframe.manage"),
                route("GET", "/api/agent/data-store/catalog", "assistant.use"),
                route("POST", "/api/agent/data-store/sync", "assistant.manage"),
                route("POST", "/api/agent/runs/42/approve-plan", "provision.approve"),
                route("POST", "/api/agent/compile", "assistant.use"),
                route("GET", "/api/unknown/read", "dashboard.read"),
                route("POST", "/api/unknown/write", "admin.all")
        );
    }

    private static Stream<Arguments> authenticatedRoutesWithoutFeaturePermission() {
        return Stream.of(
                Arguments.of("GET", "/api/auth/tokens"),
                Arguments.of("POST", "/api/auth/tokens")
        );
    }

    private static Arguments route(String method, String path, String permission) {
        return Arguments.of(method, path, permission);
    }

    private static Set<String> difference(Set<String> values, Set<String> allowed) {
        Set<String> copy = new HashSet<>(values);
        copy.removeAll(allowed);
        return copy;
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
        return new Result(response, calls.get(), audit);
    }

    private record Result(MockHttpServletResponse response, int chainCalls, AuditService audit) {}
}
