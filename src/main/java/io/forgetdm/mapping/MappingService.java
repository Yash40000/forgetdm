package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.query.QueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Service
public class MappingService {

    private final MappingRepository repo;
    private final QueryService query;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;
    private final ObjectMapper json;
    private final MappingVersionRepository versions;
    private final MappingFileAssetRepository assets;

    public MappingService(MappingRepository repo, QueryService query, DataSourceService dataSources,
                          ConnectionFactory connections, AuditService audit, ObjectMapper json,
                          MappingVersionRepository versions, MappingFileAssetRepository assets) {
        this.repo = repo; this.query = query; this.dataSources = dataSources;
        this.connections = connections; this.audit = audit; this.json = json;
        this.versions = versions; this.assets = assets;
    }

    public List<MappingEntity> list() {
        List<MappingEntity> all = repo.findAll();
        all.sort(Comparator.comparing(MappingEntity::getId).reversed());
        return all;
    }

    public MappingEntity get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Mapping " + id + " not found"));
    }

    @Transactional
    public MappingEntity save(MappingEntity in) {
        if (in.getName() == null || in.getName().isBlank()) throw ApiException.bad("Mapping name is required");
        boolean creating = in.getId() == null;
        MappingEntity m = in.getId() != null
                ? repo.findById(in.getId()).orElseThrow(() -> ApiException.notFound("Mapping " + in.getId() + " not found"))
                : new MappingEntity();
        final Long mid = m.getId();
        repo.findByNameIgnoreCase(in.getName().trim()).ifPresent(other -> {
            if (!other.getId().equals(mid)) throw ApiException.bad("A mapping named '" + in.getName() + "' already exists.");
        });
        m.setName(in.getName().trim());
        m.setDescription(in.getDescription());
        String specJson = in.getSpecJson() == null || in.getSpecJson().isBlank() ? "{}" : in.getSpecJson();
        JsonNode spec = parseSpec(specJson);
        Map<String, Object> validation = validateSpec(spec);
        if (!Boolean.TRUE.equals(validation.get("valid")))
            throw ApiException.bad("Mapping is not valid: " + String.join("; ", castStrings(validation.get("errors"))));
        m.setSpecJson(canonical(spec));
        m.setUpdatedAt(Instant.now());
        MappingEntity saved = repo.save(m);
        String hash = sha256(saved.getSpecJson());
        List<MappingVersionEntity> existing = versions.findByMappingIdOrderByVersionNoDesc(saved.getId());
        if (existing.isEmpty() || !hash.equals(existing.get(0).getSpecHash())) {
            MappingVersionEntity version = new MappingVersionEntity();
            version.setMappingId(saved.getId());
            version.setVersionNo(existing.isEmpty() ? 1 : existing.get(0).getVersionNo() + 1);
            version.setName(saved.getName());
            version.setDescription(saved.getDescription());
            version.setSpecJson(saved.getSpecJson());
            version.setSpecHash(hash);
            version.setCreatedBy(actor());
            versions.save(version);
        }
        int versionNo = existing.isEmpty() ? 1 : existing.get(0).getVersionNo()
                + (Objects.equals(existing.get(0).getSpecHash(), hash) ? 0 : 1);
        audit.record(actor(), creating ? "MAPPING_CREATED" : "MAPPING_UPDATED", "MAPPING", "mapping",
                String.valueOf(saved.getId()), saved.getName(), "SUCCESS",
                creating ? "Created mapping definition" : "Updated mapping definition",
                "{\"version\":" + versionNo + ",\"specHash\":\"" + hash + "\"}");
        return saved;
    }

    public void delete(Long id) {
        MappingEntity mapping = get(id);
        repo.deleteById(id);
        audit.record(actor(), "MAPPING_DELETED", "MAPPING", "mapping",
                String.valueOf(id), mapping.getName(), "SUCCESS", "Deleted mapping definition", null);
    }

    public List<MappingVersionEntity> versions(Long mappingId) {
        get(mappingId);
        return versions.findByMappingIdOrderByVersionNoDesc(mappingId);
    }

    @Transactional
    public MappingEntity restoreVersion(Long mappingId, Long versionId) {
        MappingEntity mapping = get(mappingId);
        MappingVersionEntity version = versions.findById(versionId)
                .filter(v -> mappingId.equals(v.getMappingId()))
                .orElseThrow(() -> ApiException.notFound("Mapping version " + versionId + " not found"));
        MappingEntity input = new MappingEntity();
        input.setId(mapping.getId()); input.setName(mapping.getName());
        input.setDescription(version.getDescription()); input.setSpecJson(version.getSpecJson());
        MappingEntity restored = save(input);
        audit.log(actor(), "MAPPING_VERSION_RESTORED", mapping.getName() + " version=" + version.getVersionNo());
        return restored;
    }

    public Map<String, Object> validateSpec(JsonNode spec) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (spec == null || !spec.isObject()) errors.add("Mapping specification must be a JSON object");
        else if (spec.has("sources") || spec.path("specVersion").asInt(1) >= 2) {
            JsonNode sources = spec.path("sources");
            if (!sources.isArray() || sources.isEmpty()) errors.add("Add at least one source");
            else {
              Set<String> aliases = new HashSet<>();
              for (int i = 0; i < sources.size(); i++) {
                JsonNode source = sources.get(i);
                String alias = source.path("alias").asText("source_" + (i + 1)).toLowerCase(Locale.ROOT);
                if (!alias.matches("[a-z_][a-z0-9_]*")) errors.add("Source " + (i + 1) + " alias must contain letters, numbers, and underscores");
                if (!aliases.add(alias)) errors.add("Source aliases must be unique: " + alias);
                String type = source.path("type").asText("DATABASE").toUpperCase(Locale.ROOT);
                if ("DATABASE".equals(type)) {
                    if (!source.hasNonNull("dataSourceId")) errors.add("Source " + (i + 1) + " needs a data source");
                    else try { dataSources.get(source.get("dataSourceId").asLong()); } catch (Exception e) { errors.add("Source " + (i + 1) + " data source does not exist"); }
                    if (source.path("table").asText().isBlank() && source.path("sql").asText().isBlank())
                        errors.add("Source " + (i + 1) + " needs a table or read-only SQL");
                } else if ("FILE".equals(type)) {
                    if (!source.hasNonNull("assetId")) errors.add("Source " + (i + 1) + " needs a managed file asset");
                    else if (!assets.existsById(source.get("assetId").asLong())) errors.add("Source " + (i + 1) + " file asset does not exist");
                } else errors.add("Source " + (i + 1) + " type must be DATABASE or FILE");
              }
              if (sources.size() > 1) {
                  JsonNode joins = spec.path("joins");
                  if (!joins.isArray() || joins.size() < sources.size() - 1) errors.add("Connect every additional source with a join rule");
                  else for (JsonNode join : joins) if (join.path("left").asText().isBlank() || join.path("right").asText().isBlank()) errors.add("Join columns cannot be blank");
              }
            }
            JsonNode target = spec.path("target");
            String targetType = target.path("type").asText("PREVIEW").toUpperCase(Locale.ROOT);
            if ("DATABASE".equals(targetType)) {
                if (!target.hasNonNull("dataSourceId")) errors.add("Database target needs a data source");
                if (target.path("table").asText().isBlank()) errors.add("Database target needs a table");
            } else if (!Set.of("FILE", "PREVIEW").contains(targetType)) errors.add("Target type must be DATABASE, FILE, or PREVIEW");
            Set<String> targets = new HashSet<>();
            JsonNode columns = spec.path("columns");
            if (!columns.isArray() || columns.isEmpty()) warnings.add("No column map is defined; source columns will pass through by name");
            else for (JsonNode column : columns) {
                String action = column.path("action").asText("COPY").toUpperCase(Locale.ROOT);
                String targetName = column.path("target").asText().trim();
                if (!"UNUSED".equals(action) && targetName.isBlank()) errors.add("Every used column needs a target name");
                if (!targetName.isBlank() && !targets.add(targetName.toLowerCase(Locale.ROOT))) errors.add("Duplicate target column: " + targetName);
                if ("COPY".equals(action) && column.path("source").asText().isBlank()) errors.add("COPY column " + targetName + " needs a source");
                if ("MASK".equals(action) && column.path("maskFunction").asText().isBlank()) errors.add("MASK column " + targetName + " needs a masking function");
                if (!Set.of("COPY", "MASK", "LITERAL", "UNUSED").contains(action)) errors.add("Unknown column action: " + action);
            }
            validateJoins(spec.path("joins"), sources, errors);
            validateTransforms(spec.path("transforms"), errors, warnings);
            validateStaging(spec.path("stagingTables"), errors);
            if (errors.isEmpty()) preflightCompiledSql(spec, errors, warnings);
            if (sources.size() > 1 && !sameDatabaseSources(sources))
                warnings.add("Cross-database/file joins use bounded federation for preview; production execution requires a saved staging/load route");
        }
        return Map.of("valid", errors.isEmpty(), "errors", errors, "warnings", warnings);
    }

    public Map<String, Object> plan(Long mappingId) {
        MappingEntity mapping = get(mappingId);
        JsonNode spec = parseSpec(mapping.getSpecJson());
        Map<String, Object> validation = validateSpec(spec);
        JsonNode target = spec.path("target");
        String targetType = target.path("type").asText(target.path("mode").asText("PREVIEW")).toUpperCase(Locale.ROOT);
        String preAction = target.path("preAction").asText("NONE").toUpperCase(Locale.ROOT);
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("VALIDATE", "Validate mapping and connector references", !Boolean.TRUE.equals(validation.get("valid")) ? "BLOCKED" : "READY"));
        steps.add(step("READ", "Read " + sourceCount(spec) + " source(s) with bounded streaming", "READY"));
        steps.add(step("TRANSFORM", "Apply column mapping, literals, and deterministic masking", "READY"));
        if (!"NONE".equals(preAction)) steps.add(step("PREPARE", preAction + " target before load", "DESTRUCTIVE"));
        steps.add(step("DELIVER", "DATABASE".equals(targetType) ? "Batch load the target and validate row counts" : "Create an encrypted downloadable " + target.path("format").asText("CSV") + " output", "READY"));
        int version = versions.findByMappingIdOrderByVersionNoDesc(mappingId).stream().findFirst().map(MappingVersionEntity::getVersionNo).orElse(1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mappingId", mappingId); out.put("mappingName", mapping.getName()); out.put("mappingVersion", version);
        out.put("valid", validation.get("valid")); out.put("errors", validation.get("errors")); out.put("warnings", validation.get("warnings"));
        out.put("targetType", targetType); out.put("preAction", preAction); out.put("destructive", !"NONE".equals(preAction));
        out.put("steps", steps); out.put("sourceCount", sourceCount(spec));
        out.put("executionMode", executionMode(spec));
        return out;
    }

    /** Preview the generated SELECT (read-only, capped) by delegating to the Data Explorer engine. */
    public Map<String, Object> preview(Long dataSourceId, String sql) {
        return query.run(dataSourceId, sql);
    }

    /** Execute the generated load SQL (INSERT … SELECT or CREATE TABLE AS …) against a target data source. */
    public Map<String, Object> load(Long dataSourceId, String sql) {
        if (dataSourceId == null) throw ApiException.bad("Target data source is required");
        if (sql == null || sql.isBlank()) throw ApiException.bad("No SQL to run");
        String s = sql.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        if (s.contains(";")) throw ApiException.bad("Only a single statement is allowed.");
        String lower = s.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("insert") || lower.startsWith("create table")))
            throw ApiException.bad("Load SQL must be an INSERT … SELECT or CREATE TABLE AS … statement.");
        DataSourceEntity ds = dataSources.get(dataSourceId);
        long start = System.currentTimeMillis();
        int rows;
        try (Connection c = connections.openPooled(ds)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) { rows = st.executeUpdate(s); }
            c.commit();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Mapping load failed: " + e.getMessage());
        }
        audit.log("system", "MAPPING_LOADED", "ds=" + dataSourceId + " rows=" + rows);
        return Map.of("ok", true, "rows", rows, "elapsedMs", System.currentTimeMillis() - start);
    }

    /**
     * Multi-target routing (Informatica Router): several INSERT…SELECT / CREATE TABLE AS statements —
     * one per router group — executed in a SINGLE transaction so a failing group rolls back all of
     * them (no half-routed loads). Each statement passes the same guards as {@link #load}.
     */
    public Map<String, Object> loadMulti(Long dataSourceId, List<String> statements) {
        if (dataSourceId == null) throw ApiException.bad("Target data source is required");
        if (statements == null || statements.isEmpty()) throw ApiException.bad("No statements to run");
        List<String> cleaned = new java.util.ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            String s = statements.get(i) == null ? "" : statements.get(i).trim();
            while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
            if (s.isEmpty()) throw ApiException.bad("Statement " + (i + 1) + " is empty");
            if (s.contains(";")) throw ApiException.bad("Statement " + (i + 1) + ": only a single statement is allowed.");
            String lower = s.toLowerCase(Locale.ROOT);
            if (!(lower.startsWith("insert") || lower.startsWith("create table")))
                throw ApiException.bad("Statement " + (i + 1) + " must be INSERT … SELECT or CREATE TABLE AS.");
            cleaned.add(s);
        }
        DataSourceEntity ds = dataSources.get(dataSourceId);
        long start = System.currentTimeMillis();
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        try (Connection c = connections.openPooled(ds)) {
            c.setAutoCommit(false);
            try {
                for (int i = 0; i < cleaned.size(); i++) {
                    try (Statement st = c.createStatement()) {
                        int rows = st.executeUpdate(cleaned.get(i));
                        results.add(Map.of("rows", rows));
                    } catch (Exception e) {
                        throw ApiException.bad("Group " + (i + 1) + " failed (all groups rolled back): " + e.getMessage());
                    }
                }
                c.commit();
            } catch (ApiException e) {
                try { c.rollback(); } catch (Exception ignore) { }
                throw e;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Multi-target load failed: " + e.getMessage());
        }
        long total = results.stream().mapToLong(r -> ((Number) r.get("rows")).longValue()).sum();
        audit.log("system", "MAPPING_MULTI_LOADED", "ds=" + dataSourceId + " groups=" + results.size() + " rows=" + total);
        return Map.of("ok", true, "results", results, "totalRows", total,
                "elapsedMs", System.currentTimeMillis() - start);
    }

    /**
     * Cross-database federation. Each source table may live in a different data source, so a single
     * SQL connection cannot join them. We read each table from its own connection (capped), then
     * hash-join in memory on the equi-join keys. Columns are qualified as "table.col". Result shape
     * matches QueryService.run so the same UI renderer works.
     */
    public Map<String, Object> federated(JsonNode spec) {
        JsonNode tablesNode = spec == null ? null : spec.path("tables");
        if (tablesNode == null || !tablesNode.isArray() || tablesNode.size() == 0)
            throw ApiException.bad("No source tables in the mapping");
        final int PER_TABLE = 5000, CAP = 1000, JOIN_BOUND = 50000;
        long start = System.currentTimeMillis();

        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> rowsByTable = new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> colsByTable = new LinkedHashMap<>();

        for (JsonNode t : tablesNode) {
            if (!t.hasNonNull("dsId")) throw ApiException.bad("Each source table needs a data source");
            long dsId = t.get("dsId").asLong();
            String schema = t.path("schema").asText(null);
            String name = t.path("name").asText(null);
            if (name == null || name.isBlank()) continue;
            String key = name.toLowerCase(Locale.ROOT);
            if (rowsByTable.containsKey(key)) continue;
            DataSourceEntity ds = dataSources.get(dsId);
            List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
            List<String> qcols = new ArrayList<>();
            try (Connection c = connections.openPooled(ds)) {
                String sch = DataSourceService.normalizeSchema(c, schema);
                String fq = (sch == null || sch.isBlank()) ? name : sch + "." + name;
                try (Statement st = c.createStatement()) {
                    st.setMaxRows(PER_TABLE);
                    try (ResultSet rs = st.executeQuery("SELECT * FROM " + fq)) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        List<String> raw = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) raw.add(md.getColumnLabel(i));
                        for (String col : raw) qcols.add(key + "." + col.toLowerCase(Locale.ROOT));
                        while (rs.next()) {
                            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= n; i++)
                                row.put(key + "." + raw.get(i - 1).toLowerCase(Locale.ROOT), cell(rs, i));
                            rows.add(row);
                        }
                    }
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw ApiException.bad("Reading table '" + name + "' failed: " + e.getMessage());
            }
            rowsByTable.put(key, rows);
            colsByTable.put(key, qcols);
        }

        List<String> order = new ArrayList<>(rowsByTable.keySet());
        if (order.isEmpty()) throw ApiException.bad("No readable source tables");

        Set<String> inResult = new HashSet<>();
        inResult.add(order.get(0));
        List<LinkedHashMap<String, Object>> result = rowsByTable.get(order.get(0));

        // Parse every wired link into an equality condition [leftTable,leftCol,rightTable,rightCol,type].
        // Multiple links between the SAME pair of tables collapse into ONE join with a composite key
        // (all conditions AND-ed) — exactly how an Informatica Joiner treats multiple conditions.
        List<String[]> remaining = new ArrayList<>();
        JsonNode joins = spec.path("joins");
        if (joins != null && joins.isArray()) {
            for (JsonNode j : joins) {
                String left = j.path("left").asText("").toLowerCase(Locale.ROOT).trim();
                String right = j.path("right").asText("").toLowerCase(Locale.ROOT).trim();
                String type = j.path("type").asText("INNER").toUpperCase(Locale.ROOT);
                if (!left.contains(".") || !right.contains(".")) continue;
                remaining.add(new String[]{
                        left.substring(0, left.indexOf('.')), left.substring(left.indexOf('.') + 1),
                        right.substring(0, right.indexOf('.')), right.substring(right.indexOf('.') + 1), type });
            }
        }

        boolean added = true;
        while (added) {
            added = false;
            // find the next table reachable from the current result through one or more conditions
            String newTable = null;
            for (String[] c : remaining) {
                boolean lJ = inResult.contains(c[0]), rJ = inResult.contains(c[2]);
                if (lJ && !rJ) { newTable = c[2]; break; }
                if (rJ && !lJ) { newTable = c[0]; break; }
            }
            if (newTable == null) break;

            // gather ALL conditions linking the current result to this new table (the composite key)
            List<String> existKeys = new ArrayList<>(), newKeys = new ArrayList<>();
            String type = "INNER";
            for (String[] c : remaining) {
                if (c[0].equals(newTable) && inResult.contains(c[2])) { newKeys.add(c[0] + "." + c[1]); existKeys.add(c[2] + "." + c[3]); type = c[4]; }
                else if (c[2].equals(newTable) && inResult.contains(c[0])) { newKeys.add(c[2] + "." + c[3]); existKeys.add(c[0] + "." + c[1]); type = c[4]; }
            }
            List<LinkedHashMap<String, Object>> newRows = rowsByTable.get(newTable);
            List<String> newCols = colsByTable.get(newTable);
            if (newRows == null) {  // unreadable side — drop its conditions so we don't loop forever
                final String nt = newTable;
                remaining.removeIf(c -> c[0].equals(nt) || c[2].equals(nt));
                continue;
            }

            Map<String, List<LinkedHashMap<String, Object>>> idx = new HashMap<>();
            for (LinkedHashMap<String, Object> r : newRows)
                idx.computeIfAbsent(compositeKey(r, newKeys), k -> new ArrayList<>()).add(r);

            boolean keepUnmatched = "LEFT".equals(type) || "FULL".equals(type);
            List<LinkedHashMap<String, Object>> merged = new ArrayList<>();
            outer:
            for (LinkedHashMap<String, Object> r : result) {
                List<LinkedHashMap<String, Object>> matches = idx.get(compositeKey(r, existKeys));
                if (matches == null || matches.isEmpty()) {
                    if (keepUnmatched) {
                        LinkedHashMap<String, Object> nr = new LinkedHashMap<>(r);
                        for (String nc : newCols) nr.put(nc, null);
                        merged.add(nr);
                    }
                } else {
                    for (LinkedHashMap<String, Object> m : matches) {
                        LinkedHashMap<String, Object> nr = new LinkedHashMap<>(r);
                        nr.putAll(m);
                        merged.add(nr);
                        if (merged.size() > JOIN_BOUND) break outer;
                    }
                }
            }
            result = merged;
            inResult.add(newTable);
            remaining.removeIf(c -> inResult.contains(c[0]) && inResult.contains(c[2])); // consumed
            added = true;
        }

        // Project all columns in table order. Tables never joined still contribute their columns
        // (cartesian-free: they simply appear as null for rows that never reached them).
        List<String> outCols = new ArrayList<>();
        for (String t : order) outCols.addAll(colsByTable.get(t));

        Integer limit = spec.path("limit").isNumber() ? spec.get("limit").asInt() : null;
        int cap = (limit != null && limit > 0) ? Math.min(limit, CAP) : CAP;
        boolean truncated = result.size() > cap;
        List<List<Object>> outRows = new ArrayList<>();
        for (int i = 0; i < Math.min(result.size(), cap); i++) {
            LinkedHashMap<String, Object> r = result.get(i);
            List<Object> row = new ArrayList<>(outCols.size());
            for (String c : outCols) row.add(r.get(c));
            outRows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", outCols);
        out.put("rows", outRows);
        out.put("rowCount", outRows.size());
        out.put("truncated", truncated);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        audit.log("system", "MAPPING_FEDERATED", "tables=" + order.size() + " rows=" + outRows.size());
        return out;
    }

    private static Object cell(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        if (v == null) return null;
        if (v instanceof Number || v instanceof Boolean) return v;
        String s = String.valueOf(v);
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private static String norm(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    /** Build a multi-column match key for a composite (AND-ed) equi-join. */
    private static String compositeKey(LinkedHashMap<String, Object> row, List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) sb.append(norm(row.get(k))).append('');
        return sb.toString();
    }

    private JsonNode parseSpec(String value) {
        try { return json.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (Exception e) { throw ApiException.bad("Mapping specification is not valid JSON: " + e.getMessage()); }
    }

    private String canonical(JsonNode spec) {
        try { return json.writeValueAsString(spec); }
        catch (Exception e) { throw ApiException.bad("Mapping specification could not be saved"); }
    }

    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStrings(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(); }
    private static String actor() { return io.forgetdm.security.AccessContext.current().map(p -> p.username()).orElse("system"); }
    private static Map<String, Object> step(String code, String label, String status) { return Map.of("code", code, "label", label, "status", status); }
    private static int sourceCount(JsonNode spec) { return spec.path("sources").isArray() ? spec.path("sources").size() : spec.path("tables").size(); }
    private static boolean sameDatabaseSources(JsonNode sources) {
        Long ds = null;
        for (JsonNode source : sources) {
            if (!"DATABASE".equalsIgnoreCase(source.path("type").asText("DATABASE")) || !source.hasNonNull("dataSourceId")) return false;
            long current = source.get("dataSourceId").asLong();
            if (ds != null && ds != current) return false;
            ds = current;
        }
        return true;
    }

    private static final Set<String> TRANSFORM_TYPES = Set.of("FILTER", "EXPRESSION", "AGGREGATOR", "SORTER", "DISTINCT", "LIMIT", "ROUTER", "UNION", "LOOKUP", "RANK", "SEQUENCE", "PIVOT");
    private static final Set<String> AGGREGATES = Set.of("SUM", "COUNT", "AVG", "MIN", "MAX");
    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final java.util.regex.Pattern SQL_TYPE = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_ ]*(?:\\(\\s*\\d+\\s*(?:,\\s*\\d+\\s*)?\\))?(?:\\[\\])?");
    private static final java.util.regex.Pattern UNSAFE_FRAGMENT = java.util.regex.Pattern.compile("(?is)(;|--|/\\*|\\*/|\\b(insert|update|delete|drop|alter|create|grant|revoke|call|execute|merge|truncate)\\b)");

    private static void validateJoins(JsonNode joins, JsonNode sources, List<String> errors) {
        if (!joins.isArray()) return;
        Set<String> aliases = new HashSet<>();
        sources.forEach(source -> aliases.add(source.path("alias").asText().toLowerCase(Locale.ROOT)));
        Set<String> pairs = new HashSet<>();
        for (int i = 0; i < joins.size(); i++) {
            JsonNode join = joins.get(i); String left = join.path("left").asText().trim(); String right = join.path("right").asText().trim();
            String label = "Join " + (i + 1);
            if (!qualifiedColumn(left) || !qualifiedColumn(right)) { errors.add(label + " must connect qualified source columns"); continue; }
            String leftAlias = left.substring(0, left.indexOf('.')).toLowerCase(Locale.ROOT), rightAlias = right.substring(0, right.indexOf('.')).toLowerCase(Locale.ROOT);
            if (!aliases.contains(leftAlias) || !aliases.contains(rightAlias)) errors.add(label + " references a source alias that is not on the canvas");
            if (leftAlias.equals(rightAlias)) errors.add(label + " must connect two different source nodes");
            String type = join.path("type").asText("INNER").toUpperCase(Locale.ROOT);
            if (!Set.of("INNER", "LEFT", "RIGHT", "FULL").contains(type)) errors.add(label + " has unsupported type " + type);
            String key = left.compareToIgnoreCase(right) <= 0 ? left.toLowerCase(Locale.ROOT) + "=" + right.toLowerCase(Locale.ROOT) : right.toLowerCase(Locale.ROOT) + "=" + left.toLowerCase(Locale.ROOT);
            if (!pairs.add(key)) errors.add("Duplicate join condition: " + left + " = " + right);
        }
    }

    private static void validateTransforms(JsonNode transforms, List<String> errors, List<String> warnings) {
        if (transforms.isMissingNode() || transforms.isNull()) return;
        if (!transforms.isArray()) { errors.add("Transformations must be an ordered array"); return; }
        Set<String> ids = new HashSet<>(), outputs = new HashSet<>();
        for (int i = 0; i < transforms.size(); i++) {
            JsonNode transform = transforms.get(i); String type = transform.path("type").asText().toUpperCase(Locale.ROOT); String label = "Transformation " + (i + 1) + " (" + (type.isBlank() ? "unknown" : type) + ")";
            if (!TRANSFORM_TYPES.contains(type)) { errors.add(label + " is not supported"); continue; }
            String id = transform.path("id").asText(); if (id.isBlank() || !ids.add(id)) errors.add(label + " needs a unique persistent id");
            if (transform.path("requiresConfiguration").asBoolean(false)) errors.add(label + " was added from the function library and must be reviewed in Transformations");
            switch (type) {
                case "FILTER" -> safeRequired(label + " condition", transform.path("condition").asText(), errors);
                case "EXPRESSION" -> validateNamedExpressions(label, transform.path("columns"), outputs, errors);
                case "AGGREGATOR" -> {
                    JsonNode aggregates = transform.path("aggregates");
                    if ((!aggregates.isArray() || aggregates.isEmpty()) && transform.path("groupBy").isEmpty()) errors.add(label + " needs group-by columns or aggregates");
                    validateNamedExpressions(label + " aggregate", aggregates, outputs, errors);
                    validateSafeArray(label + " group by", transform.path("groupBy"), errors);
                }
                case "SORTER" -> {
                    JsonNode sort = transform.path("sort"); if (!sort.isArray() || sort.isEmpty()) errors.add(label + " needs at least one sort key");
                    else for (JsonNode key : sort) { safeRequired(label + " sort column", key.path("col").asText(), errors); String direction = key.path("dir").asText("ASC").toUpperCase(Locale.ROOT); if (!Set.of("ASC", "DESC").contains(direction)) errors.add(label + " sort direction must be ASC or DESC"); }
                }
                case "LIMIT" -> { long rows = transform.path("rows").asLong(0); if (rows < 1 || rows > 1_000_000_000L) errors.add(label + " row limit must be between 1 and 1,000,000,000"); }
                case "ROUTER" -> validateRouter(label, transform.path("groups"), errors);
                case "UNION" -> { requiredIdentifier(label + " table", transform.path("table").asText(), errors); safeOptional(label + " condition", transform.path("condition").asText(), errors); safeOptional(label + " projected columns", transform.path("columns").asText(), errors); }
                case "LOOKUP" -> { requiredIdentifier(label + " lookup table", transform.path("table").asText(), errors); optionalIdentifier(label + " alias", transform.path("alias").asText(), errors); safeRequired(label + " match condition", transform.path("on").asText(), errors); safeRequired(label + " return columns", transform.path("returns").asText(), errors); }
                case "RANK" -> { safeRequired(label + " order by", transform.path("orderBy").asText(), errors); optionalIdentifier(label + " output", transform.path("name").asText(), errors); if (transform.has("topN") && transform.path("topN").asLong() < 1) errors.add(label + " Top N must be positive"); }
                case "SEQUENCE" -> { optionalIdentifier(label + " output", transform.path("name").asText(), errors); if (transform.path("orderBy").asText().isBlank()) warnings.add(label + " has no order-by expression, so sequence assignment may not be reproducible"); else safeOptional(label + " order by", transform.path("orderBy").asText(), errors); }
                case "PIVOT" -> { safeRequired(label + " category", transform.path("category").asText(), errors); safeRequired(label + " value", transform.path("value").asText(), errors); safeRequired(label + " values", transform.path("values").asText(), errors); if (!AGGREGATES.contains(transform.path("agg").asText("SUM").toUpperCase(Locale.ROOT))) errors.add(label + " aggregate must be SUM, COUNT, AVG, MIN, or MAX"); }
                default -> { }
            }
        }
    }

    private static void validateNamedExpressions(String label, JsonNode rows, Set<String> outputs, List<String> errors) {
        if (!rows.isArray() || rows.isEmpty()) { errors.add(label + " needs at least one configured expression"); return; }
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i); String name = row.path("name").asText().trim(); String expression = row.path("expr").asText().trim();
            requiredIdentifier(label + " output " + (i + 1), name, errors); safeRequired(label + " expression " + (i + 1), expression, errors);
            if (!name.isBlank() && !outputs.add(name.toLowerCase(Locale.ROOT))) errors.add("Duplicate transformation output: " + name);
        }
    }

    private static void validateRouter(String label, JsonNode groups, List<String> errors) {
        if (!groups.isArray() || groups.isEmpty()) { errors.add(label + " needs at least one route"); return; }
        Set<String> names = new HashSet<>(); boolean hasDefault = false;
        for (int i = 0; i < groups.size(); i++) {
            JsonNode group = groups.get(i); String name = group.path("name").asText().trim(); String condition = group.path("condition").asText().trim();
            if (name.isBlank() || !names.add(name.toLowerCase(Locale.ROOT))) errors.add(label + " route names must be present and unique");
            if (condition.isBlank()) { if (hasDefault || i != groups.size() - 1) errors.add(label + " may have one blank default route, and it must be last"); hasDefault = true; }
            else safeRequired(label + " route " + (i + 1) + " condition", condition, errors);
            if (!group.path("target").asText().isBlank()) requiredIdentifier(label + " route target", group.path("target").asText(), errors);
        }
    }

    private static void validateStaging(JsonNode staging, List<String> errors) {
        if (staging.isMissingNode() || staging.isNull()) return;
        if (!staging.isArray()) { errors.add("Staging objects must be an array"); return; }
        Set<String> names = new HashSet<>();
        for (JsonNode table : staging) {
            String name = table.path("name").asText().trim(); requiredIdentifier("Staging name", name, errors); if (!name.isBlank() && !names.add(name.toLowerCase(Locale.ROOT))) errors.add("Duplicate staging name: " + name);
            Set<String> columns = new HashSet<>(); if (table.path("columns").isArray()) for (JsonNode column : table.path("columns")) { String value = column.asText(); requiredIdentifier("Staging column", value, errors); if (!columns.add(value.toLowerCase(Locale.ROOT))) errors.add("Duplicate staging column " + name + "." + value); }
            JsonNode types = table.path("columnTypes"); if (types.isObject()) types.fields().forEachRemaining(entry -> { String value = entry.getValue().asText().trim(); if (!value.isBlank() && !SQL_TYPE.matcher(value).matches()) errors.add("Unsafe or invalid type for " + name + "." + entry.getKey()); });
        }
    }

    private void preflightCompiledSql(JsonNode spec, List<String> errors, List<String> warnings) {
        String sql = spec.path("compiledSql").asText().trim(); if (sql.isBlank()) return;
        if (UNSAFE_FRAGMENT.matcher(sql).find() || !(sql.toLowerCase(Locale.ROOT).startsWith("select") || sql.toLowerCase(Locale.ROOT).startsWith("with"))) { errors.add("Compiled SQL must be one read-only SELECT or WITH query"); return; }
        if (!spec.hasNonNull("compiledDataSourceId")) { errors.add("Compiled SQL needs a source data source"); return; }
        try {
            DataSourceEntity source = dataSources.get(spec.get("compiledDataSourceId").asLong());
            try (Connection connection = connections.openPooled(source); PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setMaxRows(1); statement.setQueryTimeout(15);
                try (ResultSet result = statement.executeQuery()) { if (result.getMetaData().getColumnCount() < 1) errors.add("Compiled SQL does not expose any output columns"); }
            }
        } catch (Exception e) { errors.add("Compiled transformation SQL failed database preflight: " + concise(e)); }
    }

    private static void validateSafeArray(String label, JsonNode values, List<String> errors) { if (values.isArray()) for (JsonNode value : values) safeRequired(label, value.asText(), errors); }
    private static void safeRequired(String label, String value, List<String> errors) { if (value == null || value.isBlank()) errors.add(label + " is required"); else if (UNSAFE_FRAGMENT.matcher(value).find()) errors.add(label + " contains unsafe SQL"); }
    private static void safeOptional(String label, String value, List<String> errors) { if (value != null && !value.isBlank() && UNSAFE_FRAGMENT.matcher(value).find()) errors.add(label + " contains unsafe SQL"); }
    private static void requiredIdentifier(String label, String value, List<String> errors) { if (value == null || value.isBlank()) errors.add(label + " is required"); else if (!IDENTIFIER.matcher(value).matches()) errors.add(label + " must be a simple SQL identifier"); }
    private static void optionalIdentifier(String label, String value, List<String> errors) { if (value != null && !value.isBlank() && !IDENTIFIER.matcher(value).matches()) errors.add(label + " must be a simple SQL identifier"); }
    private static boolean qualifiedColumn(String value) { if (value == null) return false; int dot = value.indexOf('.'); return dot > 0 && dot < value.length() - 1 && IDENTIFIER.matcher(value.substring(0, dot)).matches() && IDENTIFIER.matcher(value.substring(dot + 1)).matches(); }
    private static String concise(Exception error) { Throwable current = error; while (current.getCause() != null && current.getCause() != current) current = current.getCause(); String message = current.getMessage(); return message == null || message.isBlank() ? current.getClass().getSimpleName() : message.replaceAll("[\\r\\n]+", " "); }
    private static String executionMode(JsonNode spec) {
        JsonNode sources = spec.path("sources");
        if (!sources.isArray() || sources.isEmpty()) return "LEGACY_SQL";
        if (sources.size() > 1) return sameDatabaseSources(sources) ? "DATABASE_SQL_OR_STAGING" : "BOUNDED_FEDERATION_OR_STAGING";
        return "FILE".equalsIgnoreCase(sources.get(0).path("type").asText()) ? "FILE_STREAM" : "JDBC_STREAM";
    }
}
