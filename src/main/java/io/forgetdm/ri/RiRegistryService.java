package io.forgetdm.ri;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tool-level registry of primary keys and referential relationships, scoped to a user, a group, or globally.
 * Definitions are independent of any feature (masking, synthetic generation, subsetting, validation) and are
 * resolved per requesting user with precedence: user (PRIVATE) > group (GROUP) > global (GLOBAL) > live DB
 * metadata (the floor, applied by each consumer). Phase 1: define / store / scope / resolve. Consumers are
 * wired in a later phase, additively, so existing behavior is preserved until then.
 */
@Service
public class RiRegistryService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public RiRegistryService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record KeyRequest(String name, String description, String visibility, Long ownerGroupId,
                             Long dataSourceId, String schemaName, String tableName, String keyColumns) {}

    public record RelationshipRequest(String name, String description, String visibility, Long ownerGroupId,
                                      Long dataSourceId, String childSchema, String childTable, String childColumns,
                                      String parentSchema, String parentTable, String parentColumns,
                                      String relationshipType, Integer cardinalityMin, Integer cardinalityMax) {}

    // ----------------------------------------------------------------- primary keys

    public List<Map<String, Object>> listKeys(Long dataSourceId, String schema) {
        List<Map<String, Object>> rows = queryKeys(dataSourceId, schema);
        rows.removeIf(r -> !canSee(r));
        return rows;
    }

    public Map<String, Object> createKey(KeyRequest req) {
        if (req == null) throw ApiException.bad("Key definition is required");
        if (req.dataSourceId() == null) throw ApiException.bad("dataSourceId is required");
        if (blank(req.tableName())) throw ApiException.bad("tableName is required");
        if (blank(req.keyColumns())) throw ApiException.bad("At least one key column is required");
        String visibility = normalizeVisibility(req.visibility());
        Long groupId = resolveOwnerGroup(visibility, req.ownerGroupId());
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ri_primary_key(name, description, visibility, owner_user_id, owner_username, " +
                        "owner_group_id, data_source_id, schema_name, table_name, key_columns, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                blankNull(req.name()), blankNull(req.description()), visibility, currentUserId(), currentUsername(),
                groupId, req.dataSourceId(), blankNull(req.schemaName()), req.tableName().trim(),
                normalizeColumns(req.keyColumns()), ts(now), ts(now));
        audit.log(currentUsername(), "RI_PK_CREATED",
                "table=" + req.tableName() + " cols=" + normalizeColumns(req.keyColumns()) + " visibility=" + visibility);
        return Map.of("status", "created");
    }

    public Map<String, Object> updateKey(long id, KeyRequest req) {
        Map<String, Object> existing = oneKey(id);
        requireManage(existing);
        String visibility = normalizeVisibility(req.visibility());
        Long groupId = resolveOwnerGroup(visibility, req.ownerGroupId());
        jdbc.update("UPDATE ri_primary_key SET name=?, description=?, visibility=?, owner_group_id=?, " +
                        "schema_name=?, table_name=?, key_columns=?, updated_at=? WHERE id=?",
                blankNull(req.name()), blankNull(req.description()), visibility, groupId,
                blankNull(req.schemaName()), req.tableName().trim(), normalizeColumns(req.keyColumns()), ts(Instant.now()), id);
        audit.log(currentUsername(), "RI_PK_UPDATED", "id=" + id + " table=" + req.tableName());
        return Map.of("status", "updated");
    }

    public void deleteKey(long id) {
        requireManage(oneKey(id));
        jdbc.update("DELETE FROM ri_primary_key WHERE id=?", id);
        audit.log(currentUsername(), "RI_PK_DELETED", "id=" + id);
    }

    // ----------------------------------------------------------------- relationships

    public List<Map<String, Object>> listRelationships(Long dataSourceId, String schema) {
        List<Map<String, Object>> rows = queryRelationships(dataSourceId, schema);
        rows.removeIf(r -> !canSee(r));
        return rows;
    }

    public Map<String, Object> createRelationship(RelationshipRequest req) {
        if (req == null) throw ApiException.bad("Relationship definition is required");
        if (req.dataSourceId() == null) throw ApiException.bad("dataSourceId is required");
        if (blank(req.childTable()) || blank(req.parentTable())) throw ApiException.bad("child and parent tables are required");
        if (blank(req.childColumns()) || blank(req.parentColumns())) throw ApiException.bad("child and parent columns are required");
        String childCols = normalizeColumns(req.childColumns());
        String parentCols = normalizeColumns(req.parentColumns());
        if (childCols.split(",").length != parentCols.split(",").length)
            throw ApiException.bad("child and parent column counts must match (a composite key maps column-for-column)");
        String visibility = normalizeVisibility(req.visibility());
        Long groupId = resolveOwnerGroup(visibility, req.ownerGroupId());
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ri_relationship(name, description, visibility, owner_user_id, owner_username, " +
                        "owner_group_id, data_source_id, child_schema, child_table, child_columns, parent_schema, " +
                        "parent_table, parent_columns, relationship_type, cardinality_min, cardinality_max, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                blankNull(req.name()), blankNull(req.description()), visibility, currentUserId(), currentUsername(),
                groupId, req.dataSourceId(), blankNull(req.childSchema()), req.childTable().trim(), childCols,
                blankNull(req.parentSchema()), req.parentTable().trim(), parentCols, normalizeRelType(req.relationshipType()),
                req.cardinalityMin(), req.cardinalityMax(), ts(now), ts(now));
        audit.log(currentUsername(), "RI_REL_CREATED",
                req.childTable() + "(" + childCols + ") -> " + req.parentTable() + "(" + parentCols + ") visibility=" + visibility);
        return Map.of("status", "created");
    }

    public Map<String, Object> updateRelationship(long id, RelationshipRequest req) {
        requireManage(oneRelationship(id));
        String childCols = normalizeColumns(req.childColumns());
        String parentCols = normalizeColumns(req.parentColumns());
        if (childCols.split(",").length != parentCols.split(",").length)
            throw ApiException.bad("child and parent column counts must match");
        String visibility = normalizeVisibility(req.visibility());
        Long groupId = resolveOwnerGroup(visibility, req.ownerGroupId());
        jdbc.update("UPDATE ri_relationship SET name=?, description=?, visibility=?, owner_group_id=?, child_schema=?, " +
                        "child_table=?, child_columns=?, parent_schema=?, parent_table=?, parent_columns=?, " +
                        "relationship_type=?, cardinality_min=?, cardinality_max=?, updated_at=? WHERE id=?",
                blankNull(req.name()), blankNull(req.description()), visibility, groupId, blankNull(req.childSchema()),
                req.childTable().trim(), childCols, blankNull(req.parentSchema()), req.parentTable().trim(), parentCols,
                normalizeRelType(req.relationshipType()), req.cardinalityMin(), req.cardinalityMax(), ts(Instant.now()), id);
        audit.log(currentUsername(), "RI_REL_UPDATED", "id=" + id);
        return Map.of("status", "updated");
    }

    public void deleteRelationship(long id) {
        requireManage(oneRelationship(id));
        jdbc.update("DELETE FROM ri_relationship WHERE id=?", id);
        audit.log(currentUsername(), "RI_REL_DELETED", "id=" + id);
    }

    // ----------------------------------------------------------------- resolution (for consumers)

    /**
     * Effective PKs and relationships for the current user against one target, after applying precedence
     * (PRIVATE > GROUP > GLOBAL). Consumers merge this with live DB metadata as the floor.
     */
    public Map<String, Object> resolve(Long dataSourceId, String schema) {
        if (dataSourceId == null) throw ApiException.bad("dataSourceId is required");
        List<Map<String, Object>> keys = listKeys(dataSourceId, schema);
        List<Map<String, Object>> rels = listRelationships(dataSourceId, schema);

        Map<String, Map<String, Object>> bestKey = new LinkedHashMap<>();
        for (Map<String, Object> k : keys) {
            String key = String.valueOf(k.get("tableName")).toLowerCase(Locale.ROOT);
            Map<String, Object> cur = bestKey.get(key);
            if (cur == null || rank(k) > rank(cur)) bestKey.put(key, k);
        }
        Map<String, Map<String, Object>> bestRel = new LinkedHashMap<>();
        for (Map<String, Object> r : rels) {
            String key = (r.get("childTable") + "|" + r.get("childColumns") + "|" + r.get("parentTable")).toLowerCase(Locale.ROOT);
            Map<String, Object> cur = bestRel.get(key);
            if (cur == null || rank(r) > rank(cur)) bestRel.put(key, r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataSourceId", dataSourceId);
        if (schema != null) out.put("schema", schema);
        out.put("precedence", "user > group > global > live DB metadata");
        out.put("primaryKeys", new ArrayList<>(bestKey.values()));
        out.put("relationships", new ArrayList<>(bestRel.values()));
        return out;
    }

    private static int rank(Map<String, Object> r) {
        return switch (String.valueOf(r.get("visibility"))) {
            case "PRIVATE" -> 3;
            case "GROUP" -> 2;
            default -> 1;   // GLOBAL
        };
    }

    // ----------------------------------------------------------------- queries / mapping

    private List<Map<String, Object>> queryKeys(Long dataSourceId, String schema) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ri_primary_key WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (dataSourceId != null) { sql.append(" AND data_source_id = ?"); args.add(dataSourceId); }
        if (!blank(schema)) { sql.append(" AND LOWER(COALESCE(schema_name,'')) = LOWER(?)"); args.add(schema.trim()); }
        sql.append(" ORDER BY table_name, id");
        return new ArrayList<>(jdbc.query(sql.toString(), this::mapKey, args.toArray()));
    }

    private List<Map<String, Object>> queryRelationships(Long dataSourceId, String schema) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ri_relationship WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (dataSourceId != null) { sql.append(" AND data_source_id = ?"); args.add(dataSourceId); }
        if (!blank(schema)) { sql.append(" AND LOWER(COALESCE(child_schema,'')) = LOWER(?)"); args.add(schema.trim()); }
        sql.append(" ORDER BY child_table, id");
        return new ArrayList<>(jdbc.query(sql.toString(), this::mapRelationship, args.toArray()));
    }

    private Map<String, Object> oneKey(long id) {
        List<Map<String, Object>> r = jdbc.query("SELECT * FROM ri_primary_key WHERE id=?", this::mapKey, id);
        if (r.isEmpty()) throw ApiException.bad("Primary key definition not found: " + id);
        return r.get(0);
    }

    private Map<String, Object> oneRelationship(long id) {
        List<Map<String, Object>> r = jdbc.query("SELECT * FROM ri_relationship WHERE id=?", this::mapRelationship, id);
        if (r.isEmpty()) throw ApiException.bad("Relationship definition not found: " + id);
        return r.get(0);
    }

    private Map<String, Object> mapKey(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("name", rs.getString("name"));
        m.put("description", rs.getString("description"));
        m.put("visibility", rs.getString("visibility"));
        m.put("ownerUserId", (Long) rs.getObject("owner_user_id"));
        m.put("ownerUsername", rs.getString("owner_username"));
        m.put("ownerGroupId", (Long) rs.getObject("owner_group_id"));
        m.put("dataSourceId", rs.getLong("data_source_id"));
        m.put("schemaName", rs.getString("schema_name"));
        m.put("tableName", rs.getString("table_name"));
        m.put("keyColumns", rs.getString("key_columns"));
        return m;
    }

    private Map<String, Object> mapRelationship(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("name", rs.getString("name"));
        m.put("description", rs.getString("description"));
        m.put("visibility", rs.getString("visibility"));
        m.put("ownerUserId", (Long) rs.getObject("owner_user_id"));
        m.put("ownerUsername", rs.getString("owner_username"));
        m.put("ownerGroupId", (Long) rs.getObject("owner_group_id"));
        m.put("dataSourceId", rs.getLong("data_source_id"));
        m.put("childSchema", rs.getString("child_schema"));
        m.put("childTable", rs.getString("child_table"));
        m.put("childColumns", rs.getString("child_columns"));
        m.put("parentSchema", rs.getString("parent_schema"));
        m.put("parentTable", rs.getString("parent_table"));
        m.put("parentColumns", rs.getString("parent_columns"));
        m.put("relationshipType", rs.getString("relationship_type"));
        m.put("cardinalityMin", (Integer) rs.getObject("cardinality_min"));
        m.put("cardinalityMax", (Integer) rs.getObject("cardinality_max"));
        return m;
    }

    // ----------------------------------------------------------------- scoping helpers

    /** Can the current user SEE this definition (PRIVATE→owner, GROUP→member, GLOBAL→everyone, admin→all). */
    private boolean canSee(Map<String, Object> r) {
        if (isAdmin()) return true;
        String vis = String.valueOf(r.get("visibility"));
        if ("GLOBAL".equals(vis)) return true;
        Long uid = currentUserId();
        if ("PRIVATE".equals(vis)) return uid != null && uid.equals(r.get("ownerUserId"));
        if ("GROUP".equals(vis)) {
            Object gid = r.get("ownerGroupId");
            return gid instanceof Long g && myGroupIds().contains(g);
        }
        return false;
    }

    /** Can the current user MANAGE (edit/delete) this definition — owner, group member, or admin. */
    private void requireManage(Map<String, Object> r) {
        if (isAdmin()) return;
        Long uid = currentUserId();
        if (uid != null && uid.equals(r.get("ownerUserId"))) return;
        Object gid = r.get("ownerGroupId");
        if ("GROUP".equals(String.valueOf(r.get("visibility"))) && gid instanceof Long g && myGroupIds().contains(g)) return;
        throw ApiException.bad("You can only modify referential-integrity definitions you own or that are shared with your group.");
    }

    private Long resolveOwnerGroup(String visibility, Long requestedGroupId) {
        if ("GLOBAL".equals(visibility)) {
            if (!isAdmin()) throw ApiException.bad("Only an administrator can publish a GLOBAL referential-integrity definition.");
            return null;
        }
        if ("GROUP".equals(visibility)) {
            if (requestedGroupId == null) throw ApiException.bad("A group is required for GROUP visibility.");
            if (!isAdmin() && !myGroupIds().contains(requestedGroupId))
                throw ApiException.bad("You can only share a definition with a group you belong to.");
            return requestedGroupId;
        }
        return null;   // PRIVATE
    }

    /** The current user's groups (id + name), so the UI can offer GROUP-scoped sharing without admin rights. */
    public List<Map<String, Object>> myGroups() {
        Long uid = currentUserId();
        if (uid == null) return List.of();
        return jdbc.query("SELECT g.id, g.name FROM forge_groups g JOIN forge_user_groups ug ON ug.group_id = g.id " +
                        "WHERE ug.user_id = ? ORDER BY g.name",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    return m;
                }, uid);
    }

    private Set<Long> myGroupIds() {
        Long uid = currentUserId();
        if (uid == null) return Set.of();
        return new HashSet<>(jdbc.queryForList("SELECT group_id FROM forge_user_groups WHERE user_id = ?", Long.class, uid));
    }

    private static Long currentUserId() {
        return AccessContext.current().map(p -> p.userId()).orElse(null);
    }

    private static String currentUsername() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }

    private static boolean isAdmin() {
        return AccessContext.current().map(p -> p.hasPermission("admin.all")).orElse(false);
    }

    private static String normalizeVisibility(String v) {
        String s = v == null ? "PRIVATE" : v.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "PRIVATE", "GROUP", "GLOBAL" -> s;
            default -> "PRIVATE";
        };
    }

    private static String normalizeRelType(String v) {
        String s = v == null ? "NON_IDENTIFYING" : v.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "IDENTIFYING", "NON_IDENTIFYING", "OPTIONAL" -> s;
            default -> "NON_IDENTIFYING";
        };
    }

    /** Trim, drop blanks, lower-case-insensitive ordering preserved; comma-separated. */
    private static String normalizeColumns(String cols) {
        if (cols == null) return "";
        List<String> out = new ArrayList<>();
        for (String c : cols.split(",")) {
            String t = c.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return String.join(",", out);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String blankNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static java.sql.Timestamp ts(Instant i) { return i == null ? null : java.sql.Timestamp.from(i); }
}
