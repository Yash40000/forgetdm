package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Governed, metadata-only grounding store for private ForgeTDM intelligence. */
@Service
public class ForgeIntelligenceStoreService {
    private static final Duration MAX_STALENESS = Duration.ofMinutes(15);
    private static final Set<String> STOP_WORDS = Set.of(
            "about", "after", "again", "also", "and", "are", "before", "create", "data", "for", "from",
            "have", "into", "need", "our", "should", "some", "that", "the", "their", "then", "this", "through",
            "user", "using", "want", "with", "without"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ForgeIntelligenceStoreService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record SearchHit(long id, String citation, String type, String origin, String title, String summary,
                            double score, JsonNode metadata, String sensitivity, Instant updatedAt) {}

    public Map<String, Object> ensureFresh() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM forge_ai_documents WHERE active=TRUE", Integer.class);
        Instant last = lastSuccessfulSync();
        if (count == null || count == 0 || last == null || last.isBefore(Instant.now().minus(MAX_STALENESS))) {
            return sync();
        }
        return status();
    }

    public Map<String, Object> sync() {
        String actor = actor();
        long syncId = insertSync(actor);
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        try {
            jdbc.update("UPDATE forge_ai_documents SET active=FALSE WHERE origin='SYSTEM'");
            ingest("DATA_SOURCE", counts, warnings, this::dataSourceDocuments);
            ingest("DATASCOPE", counts, warnings, this::dataScopeDocuments);
            ingest("MASKING_POLICY", counts, warnings, this::policyDocuments);
            ingest("BUSINESS_ENTITY", counts, warnings, this::businessEntityDocuments);
            ingest("PII_TABLE", counts, warnings, this::classificationDocuments);
            ingest("MAPPING", counts, warnings, this::mappingDocuments);
            ingest("SAVED_JOB", counts, warnings, this::savedJobDocuments);
            ingest("SELF_SERVICE_PRODUCT", counts, warnings, this::selfServiceDocuments);
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            String message = warnings.isEmpty() ? "Forge Data Store synchronized" : String.join(" | ", warnings);
            jdbc.update("UPDATE forge_ai_store_sync_runs SET status=?,documents_written=?,source_counts_json=?,message=?,finished_at=? WHERE id=?",
                    warnings.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_WARNINGS", total, write(counts), message,
                    Timestamp.from(Instant.now()), syncId);
            Map<String, Object> out = status();
            out.put("warnings", warnings);
            return out;
        } catch (Exception e) {
            jdbc.update("UPDATE forge_ai_store_sync_runs SET status='FAILED',message=?,finished_at=? WHERE id=?",
                    clip(e.getMessage(), 1900), Timestamp.from(Instant.now()), syncId);
            throw ApiException.bad("Forge Data Store synchronization failed: " + e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> groups = jdbc.queryForList(
                "SELECT document_type AS type,COUNT(*) AS count FROM forge_ai_documents WHERE active=TRUE GROUP BY document_type ORDER BY document_type");
        long total = groups.stream().mapToLong(row -> number(row.get("count"))).sum();
        Instant last = lastSuccessfulSync();
        out.put("documents", total);
        out.put("types", groups);
        out.put("lastSyncedAt", last);
        out.put("stale", last == null || last.isBefore(Instant.now().minus(MAX_STALENESS)));
        out.put("canManage", AccessContext.current().map(principal -> principal.hasPermission("assistant.manage")).orElse(true));
        out.put("privacyBoundary", "Metadata, rules, lineage and sanitized profiles only; connection passwords and sampled PII are excluded.");
        List<Map<String, Object>> latest = jdbc.query(
                "SELECT id,status,documents_written,source_counts_json,message,triggered_by,started_at,finished_at " +
                        "FROM forge_ai_store_sync_runs ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("status", rs.getString("status"));
                    item.put("documentsWritten", rs.getInt("documents_written"));
                    item.put("sourceCounts", read(rs.getString("source_counts_json")));
                    item.put("message", rs.getString("message"));
                    item.put("triggeredBy", rs.getString("triggered_by"));
                    item.put("startedAt", instant(rs.getTimestamp("started_at")));
                    item.put("finishedAt", instant(rs.getTimestamp("finished_at")));
                    return item;
                });
        out.put("latestSync", latest.isEmpty() ? null : latest.get(0));
        return out;
    }

    public List<SearchHit> documents(String query, String type, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        if (query != null && !query.isBlank()) {
            return search(query, Math.max(limit, 20)).stream()
                    .filter(hit -> type == null || type.isBlank() || hit.type().equalsIgnoreCase(type))
                    .limit(limit).toList();
        }
        String sql = "SELECT * FROM forge_ai_documents WHERE active=TRUE";
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            sql += " AND document_type=?";
            args.add(type.trim().toUpperCase(Locale.ROOT));
        }
        sql += " ORDER BY updated_at DESC,id DESC LIMIT " + limit;
        return jdbc.query(sql, (rs, n) -> hit(rs.getLong("id"), rs.getString("document_type"), rs.getString("origin"), rs.getString("title"),
                rs.getString("summary"), 1.0, read(rs.getString("metadata_json")), rs.getString("sensitivity"),
                instant(rs.getTimestamp("updated_at"))), args.toArray());
    }

    public List<SearchHit> search(String query, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 50));
        Set<String> terms = terms(query);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,document_type,origin,title,summary,searchable_text,metadata_json,sensitivity,updated_at " +
                        "FROM forge_ai_documents WHERE active=TRUE ORDER BY updated_at DESC LIMIT 5000");
        List<SearchHit> hits = new ArrayList<>();
        String phrase = lower(query);
        for (Map<String, Object> row : rows) {
            String title = str(row.get("title"));
            String summary = str(row.get("summary"));
            String searchable = str(row.get("searchable_text"));
            String haystack = lower(title + " " + summary + " " + searchable);
            double score = phrase.isBlank() ? 0.1 : 0;
            if (!phrase.isBlank() && lower(title).contains(phrase)) score += 8;
            if (!phrase.isBlank() && haystack.contains(phrase)) score += 4;
            for (String term : terms) {
                if (lower(title).contains(term)) score += 3;
                if (haystack.contains(term)) score += 1;
            }
            String type = str(row.get("document_type"));
            if ("BUSINESS_ENTITY".equals(type) || "DATASCOPE".equals(type) || "SAVED_JOB".equals(type)) score += 0.35;
            if (score <= 0) continue;
            hits.add(hit(number(row.get("id")), type, str(row.get("origin")), title, summary, score, read(str(row.get("metadata_json"))),
                    str(row.get("sensitivity")), instant(row.get("updated_at"))));
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed().thenComparing(SearchHit::title));
        return hits.stream().limit(limit).toList();
    }

    public SearchHit addManualDocument(String type, String title, String content, JsonNode metadata) {
        if (title == null || title.isBlank()) throw ApiException.bad("Knowledge title is required");
        String cleanTitle = title.trim();
        if (cleanTitle.length() < 8 || cleanTitle.length() > 120) {
            throw ApiException.bad("Knowledge title must be between 8 and 120 characters");
        }
        if (content == null || content.isBlank()) throw ApiException.bad("Knowledge content is required");
        String normalizedType = type == null || type.isBlank() ? "BUSINESS_GLOSSARY" : type.trim().toUpperCase(Locale.ROOT);
        String key = "USER:" + UUID.randomUUID();
        upsert(key, normalizedType, "USER", cleanTitle, clip(content, 4000), clip(content, 100_000),
                metadata == null ? json.createObjectNode() : metadata, "INTERNAL", actor());
        return documents(key, null, 1).stream().findFirst().orElseGet(() -> search(title, 1).get(0));
    }

    public void removeDocument(long id) {
        boolean denied = AccessContext.current()
                .map(principal -> !principal.hasPermission("assistant.manage"))
                .orElse(false);
        if (denied) throw ApiException.forbidden("Data Store management permission is required");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,origin,title FROM forge_ai_documents WHERE id=? AND active=TRUE", id);
        if (rows.isEmpty()) throw ApiException.notFound("Data Store document " + id + " not found");
        String origin = str(rows.get(0).get("origin"));
        if ("USER".equalsIgnoreCase(origin)) {
            jdbc.update("DELETE FROM forge_ai_documents WHERE id=?", id);
            return;
        }
        jdbc.update("UPDATE forge_ai_documents SET active=FALSE,excluded=TRUE,excluded_by=?,excluded_at=?,updated_at=? WHERE id=?",
                actor(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
    }

    public void recordApprovedPlan(long runId, String goal, String summary, JsonNode intent, JsonNode plan,
                                   String fingerprint, String approvedBy) {
        var metadata = json.createObjectNode();
        metadata.put("runId", runId);
        metadata.put("fingerprint", fingerprint);
        metadata.set("intent", intent);
        metadata.set("plan", plan);
        upsert("APPROVED_PLAN:" + runId, "APPROVED_PLAN", "SYSTEM", "Approved story plan #" + runId,
                summary, goal + " " + summary + " " + intent, metadata, "INTERNAL", approvedBy);
    }

    private void ingest(String type, Map<String, Integer> counts, List<String> warnings,
                        Supplier<List<Doc>> supplier) {
        try {
            List<Doc> docs = supplier.get();
            for (Doc doc : docs) upsert(doc.key, type, "SYSTEM", doc.title, doc.summary, doc.searchable,
                    doc.metadata, "METADATA_ONLY", "system");
            counts.put(type, docs.size());
        } catch (DataAccessException e) {
            warnings.add(type + " metadata unavailable: " + clip(e.getMostSpecificCause().getMessage(), 220));
            counts.put(type, 0);
        }
    }

    private List<Doc> dataSourceDocuments() {
        return jdbc.queryForList("SELECT id,name,kind,role,environment,tags FROM data_sources ORDER BY id").stream().map(row -> {
            var meta = object(row, "id", "name", "kind", "role", "environment", "tags");
            return doc("DATA_SOURCE:" + row.get("id"), str(row.get("name")),
                    str(row.get("kind")) + " " + str(row.get("role")) + " data source",
                    join(row, "name", "kind", "role", "environment", "tags"), meta);
        }).toList();
    }

    private List<Doc> dataScopeDocuments() {
        Map<Long, List<Map<String, Object>>> profiles = group("SELECT dataset_id,table_name,included,filter_expr FROM table_profiles ORDER BY dataset_id,table_name", "dataset_id");
        return jdbc.queryForList("SELECT d.id,d.name,d.description,d.schema_name,d.driver_table,d.driver_filter,d.data_source_id,s.name AS source_name,s.kind AS source_kind " +
                "FROM dataset_definitions d LEFT JOIN data_sources s ON s.id=d.data_source_id ORDER BY d.id").stream().map(row -> {
            long id = number(row.get("id"));
            List<Map<String, Object>> tables = profiles.getOrDefault(id, List.of()).stream()
                    .filter(table -> Boolean.TRUE.equals(table.get("included"))).limit(250).toList();
            var meta = object(row, "id", "name", "schema_name", "driver_table", "data_source_id", "source_name", "source_kind");
            meta.put("includedTableCount", tables.size());
            meta.set("tables", json.valueToTree(tables));
            String tableNames = tables.stream().map(table -> str(table.get("table_name"))).reduce("", (a, b) -> a + " " + b);
            return doc("DATASCOPE:" + id, str(row.get("name")), str(row.get("description")),
                    join(row, "name", "description", "schema_name", "driver_table", "source_name", "source_kind") + tableNames, meta);
        }).toList();
    }

    private List<Doc> policyDocuments() {
        Map<Long, List<Map<String, Object>>> rules = group("SELECT policy_id,table_name,column_name,function,deterministic FROM masking_rules ORDER BY policy_id,table_name,column_name", "policy_id");
        return jdbc.queryForList("SELECT id,name,description FROM masking_policies ORDER BY id").stream().map(row -> {
            long id = number(row.get("id"));
            List<Map<String, Object>> policyRules = rules.getOrDefault(id, List.of());
            var meta = object(row, "id", "name");
            meta.put("ruleCount", policyRules.size());
            meta.set("rules", json.valueToTree(policyRules.stream().limit(500).toList()));
            String fields = policyRules.stream().map(rule -> join(rule, "table_name", "column_name", "function")).reduce("", (a, b) -> a + " " + b);
            return doc("MASKING_POLICY:" + id, str(row.get("name")), str(row.get("description")),
                    join(row, "name", "description") + fields, meta);
        }).toList();
    }

    private List<Doc> businessEntityDocuments() {
        Map<Long, List<Map<String, Object>>> members = group("SELECT entity_id,id,system_name,data_source_id,schema_name,dataset_id,logical_role,table_name,key_columns,include_in_subset,include_in_synthetic " +
                "FROM business_entity_members ORDER BY entity_id,ordinal_no,id", "entity_id");
        return jdbc.queryForList("SELECT id,name,description,domain,owner_username,primary_dataset_id,root_table,business_key_columns,status FROM business_entities ORDER BY id").stream().map(row -> {
            long id = number(row.get("id"));
            List<Map<String, Object>> entityMembers = members.getOrDefault(id, List.of());
            var meta = object(row, "id", "name", "domain", "owner_username", "primary_dataset_id", "root_table", "business_key_columns", "status");
            meta.put("memberCount", entityMembers.size());
            meta.set("members", json.valueToTree(entityMembers));
            String memberText = entityMembers.stream().map(member -> join(member, "system_name", "schema_name", "logical_role", "table_name", "key_columns")).reduce("", (a, b) -> a + " " + b);
            return doc("BUSINESS_ENTITY:" + id, str(row.get("name")), str(row.get("description")),
                    join(row, "name", "description", "domain", "root_table", "business_key_columns") + memberText, meta);
        }).toList();
    }

    private List<Doc> classificationDocuments() {
        Map<String, List<Map<String, Object>>> byTable = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT c.data_source_id,s.name AS source_name,c.table_name,c.column_name,c.data_type,c.pii_type,c.confidence,c.suggested_function,c.status " +
                "FROM classifications c LEFT JOIN data_sources s ON s.id=c.data_source_id ORDER BY c.data_source_id,c.table_name,c.column_name")) {
            byTable.computeIfAbsent(row.get("data_source_id") + ":" + row.get("table_name"), ignored -> new ArrayList<>()).add(row);
        }
        List<Doc> docs = new ArrayList<>();
        byTable.forEach((key, columns) -> {
            Map<String, Object> first = columns.get(0);
            var meta = json.createObjectNode();
            meta.put("dataSourceId", number(first.get("data_source_id")));
            meta.put("sourceName", str(first.get("source_name")));
            meta.put("tableName", str(first.get("table_name")));
            meta.put("classifiedColumnCount", columns.size());
            meta.set("columns", json.valueToTree(columns));
            String fields = columns.stream().map(row -> join(row, "column_name", "data_type", "pii_type", "suggested_function", "status")).reduce("", (a, b) -> a + " " + b);
            docs.add(doc("PII_TABLE:" + key, str(first.get("source_name")) + "." + str(first.get("table_name")),
                    columns.size() + " classified columns", join(first, "source_name", "table_name") + fields, meta));
        });
        return docs;
    }

    private List<Doc> mappingDocuments() {
        return jdbc.queryForList("SELECT id,name,description FROM mapping_definitions ORDER BY id").stream().map(row -> {
            var meta = object(row, "id", "name");
            return doc("MAPPING:" + row.get("id"), str(row.get("name")), str(row.get("description")),
                    join(row, "name", "description"), meta);
        }).toList();
    }

    private List<Doc> savedJobDocuments() {
        List<Doc> docs = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,name,description,approval_status,owner_username FROM synthetic_saved_jobs ORDER BY updated_at DESC")) {
            var meta = object(row, "id", "name", "approval_status", "owner_username");
            meta.put("jobKind", "SYNTHETIC");
            docs.add(doc("SAVED_JOB:SYNTHETIC:" + row.get("id"), str(row.get("name")), str(row.get("description")),
                    "synthetic " + join(row, "name", "description", "approval_status"), meta));
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,name,description,owner_username FROM datascope_saved_jobs ORDER BY updated_at DESC")) {
            var meta = object(row, "id", "name", "owner_username");
            meta.put("jobKind", "DATASCOPE");
            docs.add(doc("SAVED_JOB:DATASCOPE:" + row.get("id"), str(row.get("name")), str(row.get("description")),
                    "datascope subset provision " + join(row, "name", "description"), meta));
        }
        return docs;
    }

    private List<Doc> selfServiceDocuments() {
        return jdbc.queryForList("SELECT id,product_type,artifact_id,artifact_version,label,description,category,tags,approval_mode,allowed_environments " +
                "FROM self_service_products WHERE enabled=TRUE ORDER BY updated_at DESC").stream().map(row -> {
            var meta = object(row, "id", "product_type", "artifact_id", "artifact_version", "label", "category", "tags", "approval_mode", "allowed_environments");
            return doc("SELF_SERVICE_PRODUCT:" + row.get("id"), str(row.get("label")), str(row.get("description")),
                    join(row, "label", "description", "product_type", "category", "tags", "allowed_environments"), meta);
        }).toList();
    }

    private void upsert(String key, String type, String origin, String title, String summary, String searchable,
                        JsonNode metadata, String sensitivity, String actor) {
        String metadataText = metadata == null ? "{}" : metadata.toString();
        String hash = sha256(type + "\n" + title + "\n" + summary + "\n" + searchable + "\n" + metadataText);
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT id,content_hash,excluded FROM forge_ai_documents WHERE document_key=?", key);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO forge_ai_documents(document_key,document_type,origin,title,summary,searchable_text,metadata_json,sensitivity,content_hash,version_no,active,created_by,created_at,updated_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,1,TRUE,?,?,?)",
                    key, type, origin, clip(title, 300), summary, searchable, metadataText, sensitivity, hash, actor,
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            return;
        }
        boolean changed = !hash.equals(str(existing.get(0).get("content_hash")));
        boolean excluded = Boolean.TRUE.equals(existing.get(0).get("excluded"));
        jdbc.update("UPDATE forge_ai_documents SET document_type=?,origin=?,title=?,summary=?,searchable_text=?,metadata_json=?,sensitivity=?,content_hash=?," +
                        "version_no=version_no+?,active=?,updated_at=? WHERE document_key=?",
                type, origin, clip(title, 300), summary, searchable, metadataText, sensitivity, hash, changed ? 1 : 0,
                !excluded, Timestamp.from(Instant.now()), key);
    }

    private long insertSync(String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO forge_ai_store_sync_runs(status,documents_written,source_counts_json,triggered_by,started_at) VALUES ('RUNNING',0,'{}',?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, actor);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            return ps;
        }, keys);
        Number key = generatedId(keys);
        if (key == null) throw new IllegalStateException("Data Store sync id was not generated");
        return key.longValue();
    }

    private Instant lastSuccessfulSync() {
        List<Timestamp> rows = jdbc.query("SELECT finished_at FROM forge_ai_store_sync_runs WHERE status IN ('COMPLETED','COMPLETED_WITH_WARNINGS') AND finished_at IS NOT NULL ORDER BY id DESC LIMIT 1",
                (rs, n) -> rs.getTimestamp(1));
        return rows.isEmpty() ? null : instant(rows.get(0));
    }

    private Map<Long, List<Map<String, Object>>> group(String sql, String key) {
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            grouped.computeIfAbsent(number(row.get(key)), ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode object(Map<String, Object> row, String... keys) {
        var out = json.createObjectNode();
        for (String key : keys) {
            Object value = row.get(key);
            if (value == null) out.putNull(camel(key));
            else if (value instanceof Number number) out.put(camel(key), number.doubleValue() % 1 == 0 ? number.longValue() : number.doubleValue());
            else if (value instanceof Boolean bool) out.put(camel(key), bool);
            else out.put(camel(key), value.toString());
        }
        return out;
    }

    private SearchHit hit(long id, String type, String origin, String title, String summary, double score, JsonNode metadata,
                          String sensitivity, Instant updatedAt) {
        return new SearchHit(id, "FDS-" + id, type, origin, title, summary, Math.round(score * 100.0) / 100.0,
                metadata, sensitivity, updatedAt);
    }

    private Doc doc(String key, String title, String summary, String searchable, JsonNode metadata) {
        return new Doc(key, title, summary, searchable, metadata);
    }

    private record Doc(String key, String title, String summary, String searchable, JsonNode metadata) {}

    private Set<String> terms(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (String term : lower(value).split("[^a-z0-9_]+")) {
            if (term.length() >= 3 && !STOP_WORDS.contains(term)) out.add(term);
            if (out.size() >= 24) break;
        }
        return out;
    }

    private String actor() {
        return AccessContext.current().map(principal -> principal.username()).orElse("system");
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Unable to serialize Forge Data Store metadata", e); }
    }

    private JsonNode read(String value) {
        try { return value == null || value.isBlank() ? json.createObjectNode() : json.readTree(value); }
        catch (Exception e) { return json.createObjectNode().put("parseError", true); }
    }

    private static String join(Map<String, Object> row, String... keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null && !value.toString().isBlank()) out.append(' ').append(value);
        }
        return out.toString();
    }

    private static String camel(String value) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char ch : value.toCharArray()) {
            if (ch == '_') { upper = true; continue; }
            out.append(upper ? Character.toUpperCase(ch) : ch);
            upper = false;
        }
        return out.toString();
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0 : Long.parseLong(value.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        return null;
    }

    private static String str(Object value) { return value == null ? "" : value.toString(); }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).trim(); }
    private static String clip(String value, int max) { return value == null ? null : value.length() <= max ? value : value.substring(0, max); }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Number generatedId(KeyHolder keys) {
        if (keys.getKeys() != null && keys.getKeys().get("id") instanceof Number id) return id;
        if (!keys.getKeyList().isEmpty()) {
            Object value = keys.getKeyList().get(0).values().stream().filter(Number.class::isInstance).findFirst().orElse(null);
            if (value instanceof Number number) return number;
        }
        return null;
    }
}
