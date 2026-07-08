package io.forgetdm.discovery;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * User/group-scoped custom PII detection patterns. Analysts add their own column-name (NAME) or value (VALUE)
 * regexes for any PII type, scoped Private / Group / Global. {@link #resolveEffective()} returns the compiled
 * patterns for the current user with precedence user &gt; group &gt; global, which the scanner overlays on the
 * built-in patterns. CRUD is gated by discovery.read / discovery.manage (AccessControlFilter, /api/discovery/*).
 */
@Service
public class PiiPatternService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PiiPatternService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record PatternRequest(String piiType, String kind, String regex, String suggestedFunction,
                                 String description, String visibility, Long ownerGroupId) {}

    /** Compiled, precedence-resolved custom patterns for the current user. */
    public record Effective(Map<String, Pattern> name, Map<String, Pattern> value, Map<String, String> suggested) {}

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.query("SELECT * FROM pii_pattern ORDER BY pii_type, kind, id", this::map));
        rows.removeIf(r -> !canSee(r));
        return rows;
    }

    public Map<String, Object> create(PatternRequest req) {
        if (req == null) throw ApiException.bad("Pattern is required");
        String type = req.piiType() == null ? null : req.piiType().trim().toUpperCase(Locale.ROOT);
        if (type == null || type.isBlank()) throw ApiException.bad("piiType is required");
        String kind = normalizeKind(req.kind());
        String regex = validateRegex(req.regex());
        String fn = blankNull(req.suggestedFunction());
        if (fn != null) {
            fn = fn.trim().toUpperCase(Locale.ROOT);
            try { io.forgetdm.core.mask.MaskFunction.valueOf(fn); }
            catch (Exception ex) { throw ApiException.bad("Unknown masking function: " + req.suggestedFunction()); }
        }
        String visibility = normalizeVisibility(req.visibility());
        Long groupId = resolveOwnerGroup(visibility, req.ownerGroupId());
        Instant now = Instant.now();
        jdbc.update("INSERT INTO pii_pattern(pii_type, kind, regex, suggested_function, description, visibility, " +
                        "owner_user_id, owner_username, owner_group_id, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                type, kind, regex, fn, blankNull(req.description()), visibility,
                currentUserId(), currentUsername(), groupId, ts(now), ts(now));
        audit.log(currentUsername(), "PII_PATTERN_CREATED", type + "/" + kind + " visibility=" + visibility);
        return Map.of("status", "created");
    }

    public void delete(long id) {
        Map<String, Object> existing = one(id);
        requireManage(existing);
        jdbc.update("DELETE FROM pii_pattern WHERE id=?", id);
        audit.log(currentUsername(), "PII_PATTERN_DELETED", "id=" + id);
    }

    /** Distinct PII types the current user can use as scan targets, custom only (base types come from PiiPatterns). */
    public Set<String> customTypes() {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> r : list()) out.add(String.valueOf(r.get("piiType")));
        return out;
    }

    public Effective resolveEffective() {
        List<Map<String, Object>> visible = list();
        // apply lowest precedence first so user (PRIVATE) overwrites group overwrites global
        visible.sort(Comparator.comparingInt(PiiPatternService::rank).thenComparingLong(r -> ((Number) r.get("id")).longValue()));
        Map<String, Pattern> name = new LinkedHashMap<>();
        Map<String, Pattern> value = new LinkedHashMap<>();
        Map<String, String> suggested = new LinkedHashMap<>();
        for (Map<String, Object> r : visible) {
            String type = String.valueOf(r.get("piiType"));
            Pattern p = safeCompile(String.valueOf(r.get("regex")), "NAME".equals(r.get("kind")));
            if (p == null) continue;
            if ("VALUE".equals(r.get("kind"))) value.put(type, p); else name.put(type, p);
            Object fn = r.get("suggestedFunction");
            if (fn != null && !String.valueOf(fn).isBlank()) suggested.put(type, String.valueOf(fn));
        }
        return new Effective(name, value, suggested);
    }

    public List<Map<String, Object>> myGroups() {
        Long uid = currentUserId();
        if (uid == null) return List.of();
        return jdbc.query("SELECT g.id, g.name FROM forge_groups g JOIN forge_user_groups ug ON ug.group_id = g.id " +
                        "WHERE ug.user_id = ? ORDER BY g.name",
                (rs, n) -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", rs.getLong("id")); m.put("name", rs.getString("name")); return m; }, uid);
    }

    // ----------------------------------------------------------------- helpers

    private static int rank(Map<String, Object> r) {
        return switch (String.valueOf(r.get("visibility"))) { case "PRIVATE" -> 3; case "GROUP" -> 2; default -> 1; };
    }

    private Map<String, Object> one(long id) {
        List<Map<String, Object>> r = jdbc.query("SELECT * FROM pii_pattern WHERE id=?", this::map, id);
        if (r.isEmpty()) throw ApiException.bad("Custom pattern not found: " + id);
        return r.get(0);
    }

    private Map<String, Object> map(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("piiType", rs.getString("pii_type"));
        m.put("kind", rs.getString("kind"));
        m.put("regex", rs.getString("regex"));
        m.put("suggestedFunction", rs.getString("suggested_function"));
        m.put("description", rs.getString("description"));
        m.put("visibility", rs.getString("visibility"));
        m.put("ownerUserId", (Long) rs.getObject("owner_user_id"));
        m.put("ownerUsername", rs.getString("owner_username"));
        m.put("ownerGroupId", (Long) rs.getObject("owner_group_id"));
        return m;
    }

    private boolean canSee(Map<String, Object> r) {
        if (isAdmin()) return true;
        String vis = String.valueOf(r.get("visibility"));
        if ("GLOBAL".equals(vis)) return true;
        Long uid = currentUserId();
        if ("PRIVATE".equals(vis)) return uid != null && uid.equals(r.get("ownerUserId"));
        if ("GROUP".equals(vis)) { Object g = r.get("ownerGroupId"); return g instanceof Long gid && myGroupIds().contains(gid); }
        return false;
    }

    private void requireManage(Map<String, Object> r) {
        if (isAdmin()) return;
        Long uid = currentUserId();
        if (uid != null && uid.equals(r.get("ownerUserId"))) return;
        Object g = r.get("ownerGroupId");
        if ("GROUP".equals(String.valueOf(r.get("visibility"))) && g instanceof Long gid && myGroupIds().contains(gid)) return;
        throw ApiException.bad("You can only modify custom patterns you own or that are shared with your group.");
    }

    private Long resolveOwnerGroup(String visibility, Long requestedGroupId) {
        if ("GLOBAL".equals(visibility)) {
            if (!isAdmin()) throw ApiException.bad("Only an administrator can publish a GLOBAL custom pattern.");
            return null;
        }
        if ("GROUP".equals(visibility)) {
            if (requestedGroupId == null) throw ApiException.bad("A group is required for GROUP visibility.");
            if (!isAdmin() && !myGroupIds().contains(requestedGroupId))
                throw ApiException.bad("You can only share a pattern with a group you belong to.");
            return requestedGroupId;
        }
        return null;
    }

    private Set<Long> myGroupIds() {
        Long uid = currentUserId();
        if (uid == null) return Set.of();
        return new HashSet<>(jdbc.queryForList("SELECT group_id FROM forge_user_groups WHERE user_id = ?", Long.class, uid));
    }

    private static String validateRegex(String regex) {
        String r = regex == null ? null : regex.trim();
        if (r == null || r.isEmpty()) throw ApiException.bad("regex is required");
        if (r.length() > 1000) throw ApiException.bad("regex is too long (max 1000 chars)");
        try { Pattern.compile(r); } catch (Exception e) { throw ApiException.bad("Invalid regex: " + e.getMessage()); }
        return r;
    }

    private static Pattern safeCompile(String regex, boolean caseInsensitive) {
        try { return caseInsensitive ? Pattern.compile(regex, Pattern.CASE_INSENSITIVE) : Pattern.compile(regex); }
        catch (Exception e) { return null; }
    }

    private static String normalizeKind(String v) {
        String s = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
        if (!"NAME".equals(s) && !"VALUE".equals(s)) throw ApiException.bad("kind must be NAME or VALUE");
        return s;
    }

    private static String normalizeVisibility(String v) {
        String s = v == null ? "PRIVATE" : v.trim().toUpperCase(Locale.ROOT);
        return switch (s) { case "PRIVATE", "GROUP", "GLOBAL" -> s; default -> "PRIVATE"; };
    }

    private static Long currentUserId() { return AccessContext.current().map(p -> p.userId()).orElse(null); }
    private static String currentUsername() { return AccessContext.current().map(p -> p.username()).orElse("system"); }
    private static boolean isAdmin() { return AccessContext.current().map(p -> p.hasPermission("admin.all")).orElse(false); }
    private static String blankNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static java.sql.Timestamp ts(Instant i) { return i == null ? null : java.sql.Timestamp.from(i); }
}
