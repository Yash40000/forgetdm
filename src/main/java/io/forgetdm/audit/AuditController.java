package io.forgetdm.audit;

import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditEventRepository repo;
    private final AuditService audit;

    public AuditController(AuditEventRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** Filtered, paginated search. All filters optional. */
    @GetMapping
    public Map<String, Object> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int pageSize = Math.min(Math.max(size, 1), 200);
        AuditScope scope = scope();
        Page<AuditEventEntity> result = repo.searchScoped(
                scope.globalAccess(), scope.userId(), scope.groupIds(),
                blank(actor), blank(action), blank(category), blank(outcome), blank(resourceType),
                fromOrMin(from), toOrMax(to), blank(q), PageRequest.of(Math.max(page, 0), pageSize));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", result.getContent());
        out.put("total", result.getTotalElements());
        out.put("page", result.getNumber());
        out.put("size", pageSize);
        out.put("totalPages", result.getTotalPages());
        return out;
    }

    /** Distinct values for the filter dropdowns. */
    @GetMapping("/facets")
    public Map<String, Object> facets() {
        AuditScope scope = scope();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actions", repo.distinctActionsScoped(scope.globalAccess(), scope.userId(), scope.groupIds()));
        out.put("categories", repo.distinctCategoriesScoped(scope.globalAccess(), scope.userId(), scope.groupIds()));
        out.put("actors", repo.distinctActorsScoped(scope.globalAccess(), scope.userId(), scope.groupIds()));
        out.put("outcomes", List.of("SUCCESS", "FAILURE"));
        return out;
    }

    /** Headline counters. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        AuditScope scope = scope();
        List<String> categories = repo.distinctCategoriesScoped(scope.globalAccess(), scope.userId(), scope.groupIds());
        List<String> actors = repo.distinctActorsScoped(scope.globalAccess(), scope.userId(), scope.groupIds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", repo.countScoped(scope.globalAccess(), scope.userId(), scope.groupIds()));
        out.put("failures", repo.countByOutcomeScoped("FAILURE", scope.globalAccess(), scope.userId(), scope.groupIds()));
        out.put("categories", categories.size());
        out.put("actors", actors.size());
        return out;
    }

    /** Tamper-evidence: recompute the hash chain and report integrity. */
    @GetMapping("/verify")
    public Map<String, Object> verify() {
        requireAuditAdministrator();
        return audit.verifyChain();
    }

    @PostMapping("/reanchor")
    public Map<String, Object> reanchor(@RequestBody ReanchorRequest request) {
        return audit.reanchor(request == null ? null : request.reason());
    }

    public record ReanchorRequest(String reason) {}

    /** CSV export of the filtered result set (compliance evidence). */
    @GetMapping("/export.csv")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        final int EXPORT_LIMIT = 5000;
        AuditScope scope = scope();
        Page<AuditEventEntity> result = repo.searchScoped(
                scope.globalAccess(), scope.userId(), scope.groupIds(),
                blank(actor), blank(action), blank(category), blank(outcome), blank(resourceType),
                fromOrMin(from), toOrMax(to), blank(q), PageRequest.of(0, EXPORT_LIMIT));
        long matched = result.getTotalElements();
        int exported = result.getContent().size();
        boolean truncated = matched > exported;

        // DEF-0012: bulk extraction of the security record must itself be recorded, and recorded
        // BEFORE delivery, so an interrupted or refused download still leaves a trace.
        // Outcome stays within the modelled SUCCESS/FAILURE domain (the facets expose only those);
        // truncation is carried in the detail and the metadata instead of inventing a third value.
        audit.record(null, "AUDIT_EXPORTED", "SECURITY", "audit", null, "audit-trail.csv",
                "SUCCESS",
                "Exported " + exported + " of " + matched + " matching events (csv)"
                        + (truncated ? " - TRUNCATED at limit " + EXPORT_LIMIT : ""),
                "{\"format\":\"csv\",\"exported\":" + exported + ",\"matched\":" + matched
                        + ",\"limit\":" + EXPORT_LIMIT + ",\"truncated\":" + truncated + "}");

        StringBuilder csv = new StringBuilder("seq,timestamp,actor,ip,category,action,outcome,severity,resource_type,resource_id,resource_name,detail\n");
        for (AuditEventEntity e : result.getContent()) {
            csv.append(csv(e.getSeq())).append(',')
               .append(csv(e.getCreatedAt())).append(',')
               .append(csv(e.getActor())).append(',')
               .append(csv(e.getIpAddress())).append(',')
               .append(csv(e.getCategory())).append(',')
               .append(csv(e.getAction())).append(',')
               .append(csv(e.getOutcome())).append(',')
               .append(csv(e.getSeverity())).append(',')
               .append(csv(e.getResourceType())).append(',')
               .append(csv(e.getResourceId())).append(',')
               .append(csv(e.getResourceName())).append(',')
               .append(csv(e.getDetail())).append('\n');
        }
        // DEF-0011: a truncated export must never be mistakable for a complete one. Signal it both
        // in headers (for tooling) and as a trailing comment row (for a human opening the file).
        if (truncated) {
            csv.append("# TRUNCATED: exported ").append(exported).append(" of ").append(matched)
               .append(" matching events (limit ").append(EXPORT_LIMIT)
               .append("). Narrow the filter or page the API for a complete extract.\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-trail.csv\"")
                .header("X-Total-Count", String.valueOf(matched))
                .header("X-Exported-Count", String.valueOf(exported))
                .header("X-Export-Limit", String.valueOf(EXPORT_LIMIT))
                .header("X-Truncated", String.valueOf(truncated))
                .contentType(MediaType.valueOf("text/csv"))
                .body(csv.toString());
    }

    // Empty string (not null) so Postgres types the bind parameter as text — a null used inside
    // lower()/like has no inferred type and Postgres defaults it to bytea, which errors.
    private static AuditScope scope() {
        AccessPrincipal principal = AccessContext.current()
                .orElseThrow(() -> ApiException.forbidden("Authentication required"));
        Long userId = principal.userId() == null ? Long.MIN_VALUE : principal.userId();
        Set<Long> groups = principal.groupIds().isEmpty() ? Set.of(Long.MIN_VALUE) : principal.groupIds();
        return new AuditScope(principal.isAdmin(), userId, groups);
    }

    private static void requireAuditAdministrator() {
        AccessPrincipal principal = AccessContext.current()
                .orElseThrow(() -> ApiException.forbidden("Authentication required"));
        if (!principal.isAdmin()) {
            throw ApiException.forbidden("Only an audit administrator can verify the global ledger");
        }
    }

    private record AuditScope(boolean globalAccess, Long userId, Set<Long> groupIds) {}

    private static String blank(String s) { return s == null || s.isBlank() ? "" : s.trim(); }

    private static final Instant MIN_TS = Instant.EPOCH;
    private static final Instant MAX_TS = Instant.parse("9999-12-31T23:59:59Z");

    private static Instant fromOrMin(String s) {
        Instant i = parseInstant(s);
        return i != null ? i : MIN_TS;
    }

    private static Instant toOrMax(String s) {
        Instant i = parseInstant(s);
        return i != null ? i : MAX_TS;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s.trim()); } catch (Exception e) { return null; }
    }

    private static String csv(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
