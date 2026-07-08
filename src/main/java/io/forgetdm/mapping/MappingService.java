package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.query.QueryService;
import org.springframework.stereotype.Service;

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

    public MappingService(MappingRepository repo, QueryService query, DataSourceService dataSources,
                          ConnectionFactory connections, AuditService audit) {
        this.repo = repo; this.query = query; this.dataSources = dataSources;
        this.connections = connections; this.audit = audit;
    }

    public List<MappingEntity> list() {
        List<MappingEntity> all = repo.findAll();
        all.sort(Comparator.comparing(MappingEntity::getId).reversed());
        return all;
    }

    public MappingEntity get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Mapping " + id + " not found"));
    }

    public MappingEntity save(MappingEntity in) {
        if (in.getName() == null || in.getName().isBlank()) throw ApiException.bad("Mapping name is required");
        MappingEntity m = in.getId() != null
                ? repo.findById(in.getId()).orElseThrow(() -> ApiException.notFound("Mapping " + in.getId() + " not found"))
                : new MappingEntity();
        final Long mid = m.getId();
        repo.findByNameIgnoreCase(in.getName().trim()).ifPresent(other -> {
            if (!other.getId().equals(mid)) throw ApiException.bad("A mapping named '" + in.getName() + "' already exists.");
        });
        m.setName(in.getName().trim());
        m.setDescription(in.getDescription());
        m.setSpecJson(in.getSpecJson() == null || in.getSpecJson().isBlank() ? "{}" : in.getSpecJson());
        m.setUpdatedAt(Instant.now());
        MappingEntity saved = repo.save(m);
        audit.log("system", "MAPPING_SAVED", saved.getName() + " id=" + saved.getId());
        return saved;
    }

    public void delete(Long id) {
        repo.deleteById(id);
        audit.log("system", "MAPPING_DELETED", "id=" + id);
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
}
