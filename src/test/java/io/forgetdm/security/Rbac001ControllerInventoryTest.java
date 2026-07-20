package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Rbac001ControllerInventoryTest {

    private static final Set<String> PUBLIC_ROUTES = Set.of(
            "POST /api/auth/login",
            "POST /api/auth/logout",
            "GET /api/auth/me"
    );

    @Test
    void everyDiscoveredApiRouteParticipatesInTheAuthorizationContract() throws Exception {
        Set<Endpoint> endpoints = controllerEndpoints();
        assertTrue(endpoints.size() >= 150,
                () -> "Expected the complete controller inventory, but discovered only " + endpoints.size() + " endpoints");

        Set<String> knownPermissions = new LinkedHashSet<>(Set.of("security.admin"));
        RoleDefinition.ALL.forEach(role -> knownPermissions.addAll(role.permissions()));

        for (Endpoint endpoint : endpoints) {
            String route = endpoint.method() + " " + endpoint.path();
            if (PUBLIC_ROUTES.contains(route)) {
                Result publicResult = execute(endpoint, Set.of(), false);
                assertEquals(1, publicResult.chainCalls(), route + " must remain public");
                continue;
            }

            AccessControlFilter policy = new AccessControlFilter(mock(AccessControlService.class), mock(AuditService.class));
            String permission = policy.requiredPermission(endpoint.method(), endpoint.path());
            if (endpoint.path().startsWith("/api/auth/tokens")) {
                assertEquals(null, permission, route + " is documented authenticated identity self-service");
                Result authenticated = execute(endpoint, Set.of(), true);
                assertEquals(1, authenticated.chainCalls(), route + " must be available to its authenticated owner");
                continue;
            }

            assertNotNull(permission, route + " has no authorization contract");
            assertTrue(knownPermissions.contains(permission), route + " uses undocumented permission " + permission);

            Result allowed = execute(endpoint, Set.of(permission), true);
            assertEquals(1, allowed.chainCalls(), route + " rejected its exact permission " + permission);

            Result denied = execute(endpoint, Set.of("irrelevant.permission"), true);
            assertEquals(0, denied.chainCalls(), route + " reached a controller without " + permission);
            assertEquals(403, denied.response().getStatus(), route + " must return 403 when denied");
            verify(denied.audit()).record(any(), eq("ACCESS_DENIED"), any(), any(), any(), any(), any(), any(), any());
        }
    }

    private static Set<Endpoint> controllerEndpoints() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<Endpoint> endpoints = new LinkedHashSet<>();

        for (var component : scanner.findCandidateComponents("io.forgetdm")) {
            Class<?> controller = Class.forName(component.getBeanClassName());
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            Set<String> roots = paths(root);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                assertTrue(mapping.method().length > 0,
                        () -> controller.getName() + "#" + method.getName() + " must declare an HTTP method");
                for (String base : roots) {
                    for (String child : paths(mapping)) {
                        for (RequestMethod requestMethod : mapping.method()) {
                            String path = concretePath(base, child);
                            if (path.startsWith("/api/")) endpoints.add(new Endpoint(requestMethod.name(), path));
                        }
                    }
                }
            }
        }
        return endpoints;
    }

    private static Set<String> paths(RequestMapping mapping) {
        if (mapping == null) return Set.of("");
        String[] values = mapping.path().length > 0 ? mapping.path() : mapping.value();
        return values.length == 0 ? Set.of("") : new LinkedHashSet<>(Arrays.asList(values));
    }

    private static String concretePath(String base, String child) {
        String joined = (base + "/" + child).replaceAll("/{2,}", "/");
        if (joined.length() > 1 && joined.endsWith("/")) joined = joined.substring(0, joined.length() - 1);
        return joined.replaceAll("\\{[^/]+}", "42");
    }

    private static Result execute(Endpoint endpoint, Set<String> permissions, boolean authenticated) throws Exception {
        AccessControlService access = mock(AccessControlService.class);
        AuditService audit = mock(AuditService.class);
        if (authenticated) {
            AccessPrincipal principal = new AccessPrincipal(42L, "inventory-user", "Inventory User", Set.of(), permissions);
            when(access.principalFromRequest(any())).thenReturn(Optional.of(principal));
            when(access.tokenFromRequest(any())).thenReturn(Optional.empty());
        } else {
            when(access.principalFromRequest(any())).thenReturn(Optional.empty());
        }

        AccessControlFilter filter = new AccessControlFilter(access, audit);
        MockHttpServletRequest request = new MockHttpServletRequest(endpoint.method(), endpoint.path());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (req, res) -> calls.incrementAndGet();
        filter.doFilter(request, response, chain);
        return new Result(response, calls.get(), audit);
    }

    private record Endpoint(String method, String path) {}

    private record Result(MockHttpServletResponse response, int chainCalls, AuditService audit) {}
}
