package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessControlFilter extends OncePerRequestFilter {
    private final AccessControlService access;
    private final AuditService audit;

    public AccessControlFilter(AccessControlService access, AuditService audit) {
        this.access = access;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || isPublicApi(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AccessPrincipal> principal = access.principalFromRequest(request);
        if (principal.isEmpty()) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "Login required");
            return;
        }

        String permission = requiredPermission(request.getMethod(), path);
        AccessPrincipal p = principal.get();
        if (permission != null && !p.hasPermission(permission)) {
            audit.log(p.username(), "ACCESS_DENIED", request.getMethod() + " " + path + " requires " + permission);
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "You do not have permission: " + permission);
            return;
        }

        try {
            // Stash the caller's token too, so in-process self-calls (AI assistant tools) authenticate as this user.
            AccessContext.set(p, access.tokenFromRequest(request).orElse(null));
            filterChain.doFilter(request, response);
        } finally {
            AccessContext.clear();
        }
    }

    private boolean isPublicApi(String path) {
        return path.equals("/api/auth/login") || path.equals("/api/auth/logout") || path.equals("/api/auth/me");
    }

    private String requiredPermission(String method, String path) {
        String m = method.toUpperCase(Locale.ROOT);
        boolean read = "GET".equals(m);
        if (path.startsWith("/api/security")) return "security.admin";
        if (path.startsWith("/api/auth/tokens")) return null; // Any authenticated user manages only their own tokens.
        if (path.startsWith("/api/integrations")) return read ? "integration.read" : "integration.manage";
        if (path.startsWith("/api/self-service")) {
            if (path.contains("/decision/")) return "provision.approve";
            if (path.contains("/templates/")) return "datascope.manage";
            return read ? "provision.read" : "provision.run";
        }
        if (path.startsWith("/api/audit")) return "audit.read";
        if (path.startsWith("/api/dashboard")) return "dashboard.read";
        if (path.startsWith("/api/datasources")) return read ? "datasource.read" : "datasource.manage";
        if (path.startsWith("/api/query")) return "query.run";
        if (path.startsWith("/api/discovery")) return read ? "discovery.read" : "discovery.manage";
        if (path.startsWith("/api/policies")) return read || path.endsWith("/functions") || path.endsWith("/preview") ? "policy.read" : "policy.manage";
        if (path.startsWith("/api/ri")) return read ? "ri.read" : "ri.manage";
        if (path.startsWith("/api/datasets")) return read ? "datascope.read" : "datascope.manage";
        if (path.startsWith("/api/datascope")) return read ? "datascope.read" : "datascope.manage";
        if (path.startsWith("/api/business-entities")) {
            if (path.contains("/governance-requests/") && (path.endsWith("/approve") || path.endsWith("/reject"))) {
                return "provision.approve";
            }
            return read ? "datascope.read" : "datascope.manage";
        }
        if (path.startsWith("/api/subset")) return "datascope.manage";
        if (path.startsWith("/api/jobs")) {
            if (path.contains("/approval/")) return "provision.approve";
            return read ? "provision.read" : "provision.run";
        }
        if (path.startsWith("/api/synthetic")) return syntheticPermission(m, path);
        if (path.startsWith("/api/mappings")) return mappingPermission(m, path);
        if (path.startsWith("/api/unstructured")) return unstructuredPermission(m, path);
        if (path.startsWith("/api/reservations")) return read ? "reservation.read" : "reservation.manage";
        if (path.startsWith("/api/validation")) return read ? "validation.read" : "validation.run";
        if (path.startsWith("/api/virtualization")) return read ? "virtualization.read" : "virtualization.manage";
        if (path.startsWith("/api/mainframe") || path.startsWith("/api/copybook")) return read ? "mainframe.read" : "mainframe.manage";
        if (path.startsWith("/api/agent/data-store") && !read) return "assistant.manage";
        if (path.startsWith("/api/agent/runs/") && path.endsWith("/approve-plan")) return "provision.approve";
        if (path.startsWith("/api/ai") || path.startsWith("/api/agent")) return "assistant.use";
        return read ? "dashboard.read" : "admin.all";
    }

    private String syntheticPermission(String method, String path) {
        boolean read = "GET".equals(method);
        if (path.contains("/value-lists")) return read ? "synthetic.read" : "synthetic.manage";
        if (read || path.endsWith("/preview") || path.endsWith("/generators") || path.endsWith("/plan-summary")) return "synthetic.read";
        if (path.endsWith("/profile")) return "synthetic.profile";
        if (path.matches(".*/jobs/[^/]+/cancel$")) return "synthetic.cancel";
        if (path.endsWith("/approval/request")) return "synthetic.manage";
        if (path.contains("/approval/")) return "synthetic.approve";
        if (path.endsWith("/export")) return "synthetic.export";
        if (path.endsWith("/generate") || path.endsWith("/generate/start")) return "synthetic.direct.run";
        if (path.matches(".*/saved-jobs/[^/]+/run$")) return "synthetic.run";
        if (path.startsWith("/api/synthetic/saved-jobs")) return "synthetic.manage";
        return "synthetic.run";
    }

    private String mappingPermission(String method, String path) {
        boolean read = "GET".equals(method);
        if (read) return "mapping.read";
        if (path.matches(".*/runs(/[^/]+/cancel)?$") || path.matches(".*/[^/]+/runs$")) return "mapping.run";
        if (path.endsWith("/preview") || path.endsWith("/preview-spec") || path.endsWith("/federated") || path.endsWith("/validate")) return "mapping.read";
        return "mapping.manage";
    }

    private String unstructuredPermission(String method, String path) {
        boolean read = "GET".equals(method);
        if (read || path.endsWith("/preview")) return "unstructured.read";
        if (path.matches(".*/jobs/[^/]+/cancel$")) return "unstructured.cancel";
        if (path.equals("/api/unstructured/jobs")) return "unstructured.run";
        return "unstructured.manage";
    }

    private void writeJson(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + error.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}
