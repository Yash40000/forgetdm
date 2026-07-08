package io.forgetdm.datasource;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DataSourceService {
    private final DataSourceRepository repo;
    private final ConnectionFactory connections;
    private final AuditService audit;

    public DataSourceService(DataSourceRepository repo, ConnectionFactory connections, AuditService audit) {
        this.repo = repo; this.connections = connections; this.audit = audit;
    }

    public List<DataSourceEntity> list() { return repo.findAll(); }

    public DataSourceEntity get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Data source " + id + " not found"));
    }

    public DataSourceEntity create(DataSourceEntity ds) {
        DataSourceEntity saved = repo.save(ds);
        audit.log("system", "DATASOURCE_CREATED", saved.getName() + " (" + saved.getJdbcUrl() + ")");
        return saved;
    }

    public DataSourceEntity update(Long id, DataSourceEntity in) {
        DataSourceEntity ds = get(id);
        if (in.getName() != null && !in.getName().isBlank()) ds.setName(in.getName());
        if (in.getKind() != null && !in.getKind().isBlank()) ds.setKind(in.getKind());
        if (in.getJdbcUrl() != null && !in.getJdbcUrl().isBlank()) ds.setJdbcUrl(in.getJdbcUrl());
        if (in.getRole() != null && !in.getRole().isBlank()) ds.setRole(in.getRole());
        if (in.getUsername() != null) ds.setUsername(in.getUsername());
        // only overwrite the password when a new one is supplied
        if (in.getPassword() != null && !in.getPassword().isBlank()) ds.setPassword(in.getPassword());
        ds.setEnvironment(in.getEnvironment());
        ds.setTags(in.getTags());
        DataSourceEntity saved = repo.save(ds);
        audit.log("system", "DATASOURCE_UPDATED", saved.getName() + " (id=" + id + ")");
        return saved;
    }

    public void delete(Long id) {
        repo.deleteById(id);
        audit.log("system", "DATASOURCE_DELETED", "id=" + id);
    }

    public Map<String, Object> testConnection(Long id) {
        return probe(get(id));
    }

    /** Test an un-saved connection definition (from the catalog "Add connection" form). */
    public Map<String, Object> testTransient(DataSourceEntity ds) {
        if (ds == null || ds.getJdbcUrl() == null || ds.getJdbcUrl().isBlank())
            throw ApiException.bad("JDBC URL is required to test a connection");
        return probe(ds);
    }

    private Map<String, Object> probe(DataSourceEntity ds) {
        try (Connection c = connections.open(ds)) {
            DatabaseMetaData md = c.getMetaData();
            return Map.of("ok", true, "product", md.getDatabaseProductName(),
                    "version", md.getDatabaseProductVersion());
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Connection test failed: " + e.getMessage()); }
    }

    /** Foreign keys declared on a table: each {column → refTable.refColumn}. Used to wire referential generation. */
    public List<Map<String, Object>> foreignKeys(Long id, String schema, String table) {
        DataSourceEntity ds = get(id);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = connections.open(ds)) {
            String sch = normalizeSchema(c, schema);
            if (isPostgres(ds)) return postgresForeignKeys(c, sch, table);
            try (ResultSet rs = c.getMetaData().getImportedKeys(null, sch, table)) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("column", rs.getString("FKCOLUMN_NAME"));
                    m.put("refTable", rs.getString("PKTABLE_NAME"));
                    m.put("refColumn", rs.getString("PKCOLUMN_NAME"));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw ApiException.bad("Failed to read foreign keys for " + table + ": " + e.getMessage());
        }
        return out;
    }

    /** Tables of the default schema with row-count estimates skipped (cheap metadata only). */
    public List<Map<String, Object>> tables(Long id) {
        return tables(id, null);
    }

    public List<Map<String, Object>> schemas(Long id) {
        DataSourceEntity ds = get(id);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (Connection c = connections.open(ds)) {
            if (isPostgres(ds)) return postgresSchemas(c);
            String current = schemaOf(c);
            try (ResultSet rs = c.getMetaData().getSchemas()) {
                while (rs.next()) {
                    String schema = rs.getString("TABLE_SCHEM");
                    if (schema == null || SqlDialect.isSystemSchema(schema) || !seen.add(schema)) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("schema", schema);
                    row.put("current", schema.equalsIgnoreCase(String.valueOf(current)));
                    out.add(row);
                }
            }
            if (out.isEmpty() && current != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema", current);
                row.put("current", true);
                out.add(row);
            }
            out.sort((a, b) -> String.valueOf(a.get("schema")).compareToIgnoreCase(String.valueOf(b.get("schema"))));
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Failed listing schemas: " + e.getMessage()); }
    }

    /** Tables of the selected schema with row-count estimates skipped (cheap metadata only). */
    public List<Map<String, Object>> tables(Long id, String schemaName) {
        DataSourceEntity ds = get(id);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = connections.open(ds)) {
            String schema = normalizeSchema(c, schemaName);
            if (isPostgres(ds)) return postgresTables(c, schema);
            try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (SqlDialect.isSystemTable(name)) continue; // e.g. Oracle recycle-bin BIN$ tables
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("table", name);
                    t.put("schema", rs.getString("TABLE_SCHEM"));
                    out.add(t);
                }
            }
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Failed listing tables: " + e.getMessage()); }
    }

    public List<Map<String, Object>> columns(Long id, String table) {
        return columns(id, null, table);
    }

    public List<Map<String, Object>> columns(Long id, String schemaName, String table) {
        DataSourceEntity ds = get(id);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = connections.open(ds)) {
            String schema = normalizeSchema(c, schemaName);
            if (isPostgres(ds)) return postgresColumns(c, schema, table);
            try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("column", rs.getString("COLUMN_NAME"));
                    col.put("type", rs.getString("TYPE_NAME"));
                    col.put("size", rs.getInt("COLUMN_SIZE"));
                    col.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    out.add(col);
                }
            }
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Failed listing columns: " + e.getMessage()); }
    }

    public static String schemaOf(Connection c) {
        try { String s = c.getSchema(); return (s == null || s.isBlank()) ? null : s; }
        catch (Exception e) { return null; }
    }

    public static String normalizeSchema(Connection c, String schemaName) {
        return schemaName == null || schemaName.isBlank() || "__default__".equals(schemaName)
                ? schemaOf(c)
                : schemaName;
    }

    private static boolean isPostgres(DataSourceEntity ds) {
        String url = String.valueOf(ds.getJdbcUrl()).toLowerCase(Locale.ROOT);
        String kind = String.valueOf(ds.getKind()).toLowerCase(Locale.ROOT);
        return url.startsWith("jdbc:postgresql:") || kind.contains("postgres");
    }

    private static List<Map<String, Object>> postgresSchemas(Connection c) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        String current = schemaOf(c);
        String sql = """
                SELECT nspname
                FROM pg_namespace
                WHERE nspname <> 'information_schema'
                  AND nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                ORDER BY lower(nspname)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String schema = rs.getString("nspname");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema", schema);
                row.put("current", schema != null && schema.equalsIgnoreCase(String.valueOf(current)));
                out.add(row);
            }
        }
        if (out.isEmpty() && current != null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schema", current);
            row.put("current", true);
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> postgresTables(Connection c, String schema) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = """
                SELECT table_name, table_schema
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("table_name");
                    if (SqlDialect.isSystemTable(name)) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("table", name);
                    row.put("schema", rs.getString("table_schema"));
                    out.add(row);
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> postgresColumns(Connection c, String schema, String table) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = """
                SELECT column_name, data_type, udt_name, character_maximum_length,
                       numeric_precision, datetime_precision, is_nullable
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                ORDER BY ordinal_position
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("data_type");
                    if ("USER-DEFINED".equalsIgnoreCase(type)) type = rs.getString("udt_name");
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("column", rs.getString("column_name"));
                    col.put("type", type);
                    col.put("size", firstInt(rs, "character_maximum_length", "numeric_precision", "datetime_precision"));
                    col.put("nullable", "YES".equalsIgnoreCase(rs.getString("is_nullable")));
                    out.add(col);
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> postgresForeignKeys(Connection c, String schema, String table) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = """
                SELECT a.attname AS column_name, ref_class.relname AS ref_table, ref_attr.attname AS ref_column
                FROM pg_constraint con
                JOIN pg_class child_class ON child_class.oid = con.conrelid
                JOIN pg_namespace child_ns ON child_ns.oid = child_class.relnamespace
                JOIN pg_class ref_class ON ref_class.oid = con.confrelid
                JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS child_cols(attnum, ord) ON TRUE
                JOIN LATERAL unnest(con.confkey) WITH ORDINALITY AS ref_cols(attnum, ord) ON ref_cols.ord = child_cols.ord
                JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = child_cols.attnum
                JOIN pg_attribute ref_attr ON ref_attr.attrelid = con.confrelid AND ref_attr.attnum = ref_cols.attnum
                WHERE con.contype = 'f'
                  AND child_ns.nspname = ?
                  AND child_class.relname = ?
                ORDER BY con.conname, child_cols.ord
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("column", rs.getString("column_name"));
                    row.put("refTable", rs.getString("ref_table"));
                    row.put("refColumn", rs.getString("ref_column"));
                    out.add(row);
                }
            }
        }
        return out;
    }

    private static int firstInt(ResultSet rs, String... names) throws SQLException {
        for (String name : names) {
            Object value = rs.getObject(name);
            if (value instanceof Number n && n.intValue() > 0) return n.intValue();
        }
        return 0;
    }

}
