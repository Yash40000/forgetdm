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
    private final io.forgetdm.security.OwnershipGuard ownership;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DataSourceService(DataSourceRepository repo, ConnectionFactory connections, AuditService audit,
                             io.forgetdm.security.OwnershipGuard ownership,
                             org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repo = repo; this.connections = connections; this.audit = audit; this.ownership = ownership;
        this.jdbc = jdbc;
    }

    /** Tenant-scoped: only sources the caller owns, their group owns, or that are SHARED. */
    public List<DataSourceEntity> list() {
        return repo.findAll().stream()
                .filter(ds -> ownership.canSee(ds.getOwnerUserId(), ds.getOwnerGroupId(), ds.getVisibility()))
                .toList();
    }

    /**
     * Object-level tenancy gate. Every read/browse/test path (schemas, tables, columns, fks,
     * diagnostics, testConnection) resolves the source through here, so guarding it closes them all —
     * important because a data source carries the JDBC URL and credentials.
     */
    public DataSourceEntity get(Long id) {
        DataSourceEntity ds = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Data source " + id + " not found"));
        ownership.assertCanSee("data source", id, ds.getOwnerUserId(), ds.getOwnerGroupId(), ds.getVisibility());
        return ds;
    }

    public DataSourceEntity getSourceCapable(Long id) {
        DataSourceEntity ds = get(id);
        requireCapability(ds, "SOURCE");
        return ds;
    }

    public DataSourceEntity getTargetCapable(Long id) {
        DataSourceEntity ds = get(id);
        requireCapability(ds, "TARGET");
        return ds;
    }

    private static void requireCapability(DataSourceEntity ds, String capability) {
        String role = ds.getRole() == null ? "" : ds.getRole().trim().toUpperCase(Locale.ROOT);
        if (!"BOTH".equals(role) && !capability.equals(role)) {
            throw ApiException.bad("Data source '" + ds.getName() + "' is not "
                    + capability.toLowerCase(Locale.ROOT) + "-capable (role=" + role + ")");
        }
    }

    /** Roles the platform understands. */
    private static final java.util.Set<String> VALID_ROLES = java.util.Set.of("SOURCE", "TARGET", "BOTH");

    /** Engine identifiers accepted by {@link SqlDialect#of}, including its aliases. */
    private static final java.util.Set<String> VALID_KINDS = java.util.Set.of(
            "POSTGRES", "POSTGRESQL", "H2", "MYSQL", "MARIADB",
            "DB2", "DB2UDB", "DB2_UDB", "DB2LUW", "DB2ZOS",
            "ORACLE", "SQLSERVER", "SQL_SERVER", "MSSQL",
            "TERADATA", "VANTAGE", "GENERIC");

    /**
     * Validate before persisting (DEF-0017). The endpoint previously accepted a blank name, a
     * malformed JDBC URL, an unknown role and an unsupported engine — all with 200 — because nothing
     * checked them: {@code SqlDialect.of} silently falls back to GENERIC for an unknown kind, and the
     * only rejections came from database constraints, surfacing as 500s.
     *
     * @param id the row being updated, or null when creating (so a rename can keep its own name)
     */
    private void validate(DataSourceEntity ds, Long id) {
        String name = ds.getName() == null ? "" : ds.getName().trim();
        if (name.length() < 3 || name.length() > 120) {
            throw ApiException.bad("Connection name must be between 3 and 120 characters");
        }
        boolean nameTaken = repo.findAll().stream()
                .anyMatch(other -> name.equalsIgnoreCase(other.getName() == null ? "" : other.getName().trim())
                        && !other.getId().equals(id));
        if (nameTaken) throw ApiException.conflict("A connection named '" + name + "' already exists");
        ds.setName(name);

        String url = ds.getJdbcUrl() == null ? "" : ds.getJdbcUrl().trim();
        if (!url.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:")) {
            throw ApiException.bad("JDBC URL is required and must start with 'jdbc:'");
        }
        ds.setJdbcUrl(url);

        String role = ds.getRole() == null ? "" : ds.getRole().trim().toUpperCase(java.util.Locale.ROOT);
        if (!VALID_ROLES.contains(role)) {
            throw ApiException.bad("Role must be one of SOURCE, TARGET or BOTH");
        }
        ds.setRole(role);

        String kind = ds.getKind() == null ? "" : ds.getKind().trim().toUpperCase(java.util.Locale.ROOT);
        if (!VALID_KINDS.contains(kind)) {
            throw ApiException.bad("Unsupported engine '" + ds.getKind() + "'. Supported: POSTGRES, H2, "
                    + "MYSQL, DB2, ORACLE, SQLSERVER, TERADATA, GENERIC");
        }
        ds.setKind(kind);
    }

    public DataSourceEntity create(DataSourceEntity ds) {
        validate(ds, null);
        ds.setOwnerUserId(ownership.defaultOwnerUserId());
        ds.setOwnerUsername(ownership.defaultOwnerUsername());
        ds.setOwnerGroupId(ownership.defaultOwnerGroupId());
        if (ds.getVisibility() == null || ds.getVisibility().isBlank()) {
            ds.setVisibility(ownership.defaultVisibility());
        }
        DataSourceEntity saved = repo.save(ds);
        audit.record(ownership.defaultOwnerUsername(), "DATASOURCE_CREATED", "DATA_SOURCE",
                "datasource", String.valueOf(saved.getId()), saved.getName(), "SUCCESS",
                saved.getName() + " (role=" + saved.getRole() + " engine=" + saved.getKind()
                        + " url=" + safeJdbc(saved.getJdbcUrl()) + ")", null);
        return saved;
    }

    /**
     * Strip credentials from a JDBC URL before it is logged or audited (DEF-0013).
     *
     * <p>JDBC URLs are a conventional place to carry secrets — {@code //user:pass@host} userinfo and
     * {@code ?user=…&password=…} parameters. The audit trail is broadly readable and CSV-exportable,
     * so a credential-bearing URL recorded verbatim is a wide disclosure. Host/port/database are kept
     * because they are the useful part of the record.
     */
    static String safeJdbc(String url) {
        if (url == null || url.isBlank()) return "";
        String out = url;
        out = out.replaceAll("(?i)://[^/@\\s]+:[^/@\\s]+@", "://");                  // //user:pass@host
        out = out.replaceAll("(?i)([?&;])(user|username|password|pwd|passwd)=[^&;\\s]*", "$1$2=***");
        return out;
    }

    public DataSourceEntity update(Long id, DataSourceEntity in) {
        DataSourceEntity ds = get(id);
        if (in.getVersion() == null) {
            throw ApiException.conflict("Connection version is required. Refresh the connection and retry your changes.");
        }
        if (!java.util.Objects.equals(in.getVersion(), ds.getVersion())) {
            throw ApiException.conflict("Connection '" + ds.getName()
                    + "' changed after you opened it. Refresh and reapply your changes.");
        }
        if (in.getName() != null && !in.getName().isBlank()) ds.setName(in.getName());
        if (in.getKind() != null && !in.getKind().isBlank()) ds.setKind(in.getKind());
        if (in.getJdbcUrl() != null && !in.getJdbcUrl().isBlank()) ds.setJdbcUrl(in.getJdbcUrl());
        if (in.getRole() != null && !in.getRole().isBlank()) ds.setRole(in.getRole());
        if (in.getUsername() != null) ds.setUsername(in.getUsername());
        // only overwrite the password when a new one is supplied
        if (in.getPassword() != null && !in.getPassword().isBlank()) ds.setPassword(in.getPassword());
        ds.setEnvironment(in.getEnvironment());
        ds.setTags(in.getTags());
        validate(ds, id);                       // edits must not bypass the create-time rules (DEF-0017)
        DataSourceEntity saved = repo.save(ds);
        connections.evict(id);
        // Actor/role/engine recorded so the trail answers "who changed which connection, to what"
        // (DSRC-001-08). The URL is redacted by safeJdbc (DEF-0013).
        audit.record(ownership.defaultOwnerUsername(), "DATASOURCE_UPDATED", "DATA_SOURCE",
                "datasource", String.valueOf(id), saved.getName(), "SUCCESS",
                saved.getName() + " (id=" + id + " role=" + saved.getRole() + " engine=" + saved.getKind()
                        + " url=" + safeJdbc(saved.getJdbcUrl()) + ")", null);
        return saved;
    }

    /**
     * Name what is blocking a delete (DSRC-001-06).
     *
     * <p>Without this the foreign key still protects the data, but the caller gets an opaque
     * constraint failure and has no way to discover *which* policies or blueprints to unpick. The
     * check runs as plain SQL rather than through the owning services because {@code DataSetService}
     * already depends on this class — going the other way would be a circular bean dependency.
     */
    private List<String> dependentsOf(Long id) {
        List<String> names = new java.util.ArrayList<>();
        collect(names, "policy", "SELECT name FROM masking_policies WHERE data_source_id = ?", id);
        collect(names, "blueprint",
                "SELECT name FROM dataset_definitions WHERE data_source_id = ? OR target_data_source_id = ?", id, id);
        collect(names, "reservation",
                "SELECT DISTINCT table_name FROM reservations WHERE data_source_id = ? AND status = 'ACTIVE'", id);
        return names;
    }

    private void collect(List<String> into, String label, String sql, Object... args) {
        try {
            for (String n : jdbc.queryForList(sql, String.class, args)) {
                if (into.size() < 10) into.add(label + " '" + n + "'");
            }
        } catch (Exception ignore) {
            // A missing/renamed table must never block a delete that the FK would allow.
        }
    }

    public void delete(Long id) {
        DataSourceEntity ds = get(id);   // tenancy-checked
        List<String> blockers = dependentsOf(id);
        if (!blockers.isEmpty()) {
            throw ApiException.conflict("Cannot delete '" + ds.getName() + "' because it is still referenced by: "
                    + String.join(", ", blockers) + ". Remove or repoint these first.");
        }
        connections.evict(id);
        repo.deleteById(id);
        audit.record(ownership.defaultOwnerUsername(), "DATASOURCE_DELETED", "DATA_SOURCE",
                "datasource", String.valueOf(id), ds.getName(), "SUCCESS",
                "id=" + id + " name=" + ds.getName() + " role=" + ds.getRole() + " engine=" + ds.getKind(), null);
    }

    /**
     * Testing a saved connection exercises stored credentials against a remote system, so it is a
     * credential-use event and must be auditable (DSRC-001-08) — previously it left no trace at all.
     * Both outcomes are recorded: a run of failures is exactly the signal an operator wants to see.
     */
    public Map<String, Object> testConnection(Long id) {
        DataSourceEntity ds = get(id);            // tenancy-checked
        Map<String, Object> result = probe(ds);
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        audit.record(ownership.defaultOwnerUsername(), "DATASOURCE_TESTED", "DATA_SOURCE",
                "datasource", String.valueOf(id), ds.getName(), ok ? "SUCCESS" : "FAILURE",
                ds.getName() + " (engine=" + ds.getKind() + " url=" + safeJdbc(ds.getJdbcUrl()) + ")"
                        + (ok ? "" : " - " + String.valueOf(result.get("message"))),
                null);
        return result;
    }

    /** Test an un-saved connection definition (from the catalog "Add connection" form). */
    public Map<String, Object> testTransient(DataSourceEntity ds) {
        if (ds == null || ds.getJdbcUrl() == null || ds.getJdbcUrl().isBlank())
            throw ApiException.bad("JDBC URL is required to test a connection");
        return probe(ds);
    }

    private Map<String, Object> probe(DataSourceEntity ds) {
        long started = System.currentTimeMillis();
        try (Connection c = connections.openPooled(ds)) {
            DatabaseMetaData md = c.getMetaData();
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("product", md.getDatabaseProductName());
            ok.put("version", md.getDatabaseProductVersion());
            ok.put("elapsedMs", System.currentTimeMillis() - started);   // DSRC-002-01
            return ok;
        } catch (Exception e) {
            throw ApiException.bad(classify(e, ds, System.currentTimeMillis() - started));
        }
    }

    /**
     * Turn a driver failure into a distinct, actionable message (DSRC-002-03/04/05).
     *
     * <p>Previously every failure surfaced as the driver's own prose. For PostgreSQL that is adequate
     * for auth and refusal, but a DNS failure and a connect timeout both produce the identical string
     * "The connection attempt failed." — measured live at 183 ms and 8179 ms respectively, needing
     * completely different remediation. The caller could not tell them apart, and nothing was
     * machine-readable.
     *
     * <p>Classification keys off SQLState (a cross-vendor standard) first, then the root cause type,
     * so it degrades sensibly on engines other than PostgreSQL. The category token is prefixed to the
     * message so it is greppable and UI-branchable without changing the HTTP contract.
     */
    private String classify(Exception e, DataSourceEntity ds, long elapsedMs) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();

        String sqlState = null;
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sqlEx && sqlEx.getSQLState() != null) { sqlState = sqlEx.getSQLState(); break; }
        }
        String host = hostOf(ds.getJdbcUrl());
        String state = sqlState == null ? "" : sqlState;

        // Authentication / authorisation - never echo the credential itself. Connection pools
        // occasionally wrap a vendor authentication exception without preserving SQLState, so
        // retain a deliberately narrow message fallback for the common driver forms.
        if (state.startsWith("28") || isAuthenticationFailure(e)) {
            return "[AUTH] Authentication failed for user '" + nz(ds.getUsername()) + "'. Check the username and password.";
        }
        if ("3D000".equals(state)) {
            return "[DATABASE_MISSING] The database named in the JDBC URL does not exist on " + host + ".";
        }
        if ("42501".equals(state)) {
            return "[PRIVILEGE] Connected, but the account lacks the required privileges.";
        }
        // TLS must fail closed and say so - never silently downgrade.
        if (root instanceof javax.net.ssl.SSLException
                || root.getClass().getName().contains("CertPath")
                || String.valueOf(root.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("certificate")) {
            return "[TLS] The TLS handshake failed against " + host + ": " + rootText(root)
                    + ". Trust the server certificate or correct the hostname - the connection was refused, not downgraded.";
        }
        if (root instanceof java.net.SocketTimeoutException
                || String.valueOf(root.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("timed out")
                || elapsedMs >= 7500) {   // at/over the configured login-timeout budget
            return "[TIMEOUT] No response from " + host + " within " + elapsedMs + " ms. "
                    + "The host is reachable-but-silent or blocked by a firewall; check routing, or raise "
                    + "FORGETDM_JDBC_LOGIN_TIMEOUT_SECONDS.";
        }
        if (isDnsFailure(e, host)) {
            return "[DNS] Host '" + host + "' could not be resolved. Check the hostname in the JDBC URL.";
        }
        if (root instanceof java.net.ConnectException || state.startsWith("08") || isNetworkFailure(e)) {
            return "[NETWORK] Cannot reach " + host + ": " + rootText(root)
                    + ". Check the host and port, and that the database is accepting TCP connections.";
        }
        return "[UNKNOWN] Connection test failed against " + host + ": " + rootText(root);
    }

    private static boolean isDnsFailure(Throwable error, String host) {
        for (Throwable current = error; current != null && current.getCause() != current;
             current = current.getCause()) {
            if (current instanceof java.net.UnknownHostException) return true;
        }
        String message = messages(error);
        if (!message.contains("connection attempt failed")) return false;
        String name = hostName(host);
        if (name == null) return false;
        try {
            java.net.InetAddress.getByName(name);
            return false;
        } catch (java.net.UnknownHostException ignored) {
            return true;
        }
    }

    private static boolean isNetworkFailure(Throwable error) {
        String message = messages(error);
        return message.contains("connection refused")
                || (message.contains("connection to ") && message.contains(" refused"))
                || message.contains(" no route to host")
                || message.contains("network is unreachable");
    }

    private static String messages(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = error; current != null && current.getCause() != current;
             current = current.getCause()) {
            all.append(' ').append(String.valueOf(current.getMessage()));
        }
        return all.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static String hostName(String host) {
        if (host == null || host.isBlank() || "the server".equals(host)) return null;
        if (host.startsWith("[")) {
            int closing = host.indexOf(']');
            return closing > 1 ? host.substring(1, closing) : null;
        }
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    private static boolean isAuthenticationFailure(Throwable error) {
        for (Throwable current = error; current != null && current.getCause() != current;
             current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("password authentication failed")
                    || message.contains("wrong user name or password")
                    || message.contains("access denied for user")
                    || message.contains("login failed for user")) {
                return true;
            }
        }
        return false;
    }

    /** Root message without the exception class name or any stack detail. */
    private static String rootText(Throwable root) {
        String m = root.getMessage();
        return m == null || m.isBlank() ? root.getClass().getSimpleName() : m.trim();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * host:port from a JDBC URL, for error messages — deliberately never the whole URL (DEF-0013).
     *
     * <p>Any {@code user:password@} userinfo is dropped: a URL like
     * {@code //alice:s3cret@dbhost:5432/db} must yield {@code dbhost:5432}, otherwise the credential
     * would travel into a message that is shown in the UI and written to logs.
     */
    static String hostOf(String jdbcUrl) {
        if (jdbcUrl == null) return "the server";
        String candidate = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("//([^/?;,\\s]+)").matcher(jdbcUrl);
        if (m.find()) {
            candidate = m.group(1);
        } else {
            java.util.regex.Matcher at = java.util.regex.Pattern
                    .compile("@(?://)?([^/?;,\\s]+)").matcher(jdbcUrl); // Oracle: @host or @//host
            if (at.find()) candidate = at.group(1);
        }
        if (candidate == null || candidate.isBlank()) return "the server";
        int userinfo = candidate.lastIndexOf('@');               // strip user:pass@
        if (userinfo >= 0) candidate = candidate.substring(userinfo + 1);
        return candidate.isBlank() ? "the server" : candidate;
    }

    /** Foreign keys declared on a table: each {column → refTable.refColumn}. Used to wire referential generation. */
    public List<Map<String, Object>> foreignKeys(Long id, String schema, String table) {
        DataSourceEntity ds = get(id);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = connections.openPooled(ds)) {
            String sch = normalizeSchema(c, schema);
            if (isPostgres(ds)) return postgresForeignKeys(c, sch, table);
            SqlDialect dialect = SqlDialect.of(ds);
            String catalog = dialect == SqlDialect.MYSQL ? (sch == null ? c.getCatalog() : sch) : null;
            String schemaPattern = dialect == SqlDialect.MYSQL ? null : sch;
            try (ResultSet rs = c.getMetaData().getImportedKeys(catalog, schemaPattern, table)) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("column", rs.getString("FKCOLUMN_NAME"));
                    m.put("fkName", rs.getString("FK_NAME"));
                    m.put("sequence", rs.getShort("KEY_SEQ"));
                    m.put("refTable", rs.getString("PKTABLE_NAME"));
                    m.put("refColumn", rs.getString("PKCOLUMN_NAME"));
                    m.put("refSchema", rs.getString("PKTABLE_SCHEM"));
                    m.put("updateRule", rs.getShort("UPDATE_RULE"));
                    m.put("deleteRule", rs.getShort("DELETE_RULE"));
                    m.put("deferrability", rs.getShort("DEFERRABILITY"));
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
        try (Connection c = connections.openPooled(ds)) {
            if (isPostgres(ds)) return postgresSchemas(c);
            if (SqlDialect.of(ds) == SqlDialect.MYSQL) return catalogSchemas(c);
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
        try (Connection c = connections.openPooled(ds)) {
            String schema = normalizeSchema(c, schemaName);
            if (isPostgres(ds)) return postgresTables(c, schema);
            SqlDialect dialect = SqlDialect.of(ds);
            String catalog = dialect == SqlDialect.MYSQL ? (schema == null ? c.getCatalog() : schema) : null;
            String schemaPattern = dialect == SqlDialect.MYSQL ? null : schema;
            try (ResultSet rs = c.getMetaData().getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (SqlDialect.isSystemTable(name)) continue; // e.g. Oracle recycle-bin BIN$ tables
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("table", name);
                    t.put("schema", rs.getString("TABLE_SCHEM"));
                    t.put("type", rs.getString("TABLE_TYPE"));
                    t.put("remarks", rs.getString("REMARKS"));
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
        try (Connection c = connections.openPooled(ds)) {
            String schema = normalizeSchema(c, schemaName);
            if (isPostgres(ds)) return postgresColumns(c, schema, table);
            SqlDialect dialect = SqlDialect.of(ds);
            String catalog = dialect == SqlDialect.MYSQL ? (schema == null ? c.getCatalog() : schema) : null;
            String schemaPattern = dialect == SqlDialect.MYSQL ? null : schema;
            try (ResultSet rs = c.getMetaData().getColumns(catalog, schemaPattern, table, "%")) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("column", rs.getString("COLUMN_NAME"));
                    col.put("type", rs.getString("TYPE_NAME"));
                    col.put("jdbcType", rs.getInt("DATA_TYPE"));
                    col.put("size", rs.getInt("COLUMN_SIZE"));
                    col.put("scale", rs.getInt("DECIMAL_DIGITS"));
                    col.put("ordinal", rs.getInt("ORDINAL_POSITION"));
                    col.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    col.put("defaultValue", rs.getString("COLUMN_DEF"));
                    col.put("autoIncrement", "YES".equalsIgnoreCase(safeString(rs, "IS_AUTOINCREMENT")));
                    col.put("generated", "YES".equalsIgnoreCase(safeString(rs, "IS_GENERATEDCOLUMN")));
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
        if (schemaName == null || schemaName.isBlank() || "__default__".equals(schemaName)) {
            return schemaOf(c);
        }

        String requested = schemaName.trim();
        // DB2 and Oracle normally expose unquoted schemas in upper case. JDBC
        // metadata matching is often case-sensitive, so retain the exact physical
        // spelling when a user types "omd1" for schema OMD1.
        try (ResultSet rs = c.getMetaData().getSchemas()) {
            while (rs.next()) {
                String physical = rs.getString("TABLE_SCHEM");
                if (physical != null && physical.equalsIgnoreCase(requested)) return physical;
            }
        } catch (Exception ignored) {
            // Some drivers do not implement getSchemas. Do not replace an explicit
            // user choice with the connection's unrelated default schema.
        }
        return requested;
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

    private static List<Map<String, Object>> catalogSchemas(Connection c) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        String current = c.getCatalog();
        try (ResultSet rs = c.getMetaData().getCatalogs()) {
            while (rs.next()) {
                String catalog = rs.getString("TABLE_CAT");
                if (catalog == null || SqlDialect.isSystemSchema(catalog)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema", catalog);
                row.put("current", catalog.equalsIgnoreCase(String.valueOf(current)));
                out.add(row);
            }
        }
        out.sort((a, b) -> String.valueOf(a.get("schema")).compareToIgnoreCase(String.valueOf(b.get("schema"))));
        return out;
    }

    /** True if the physical schema exists. Distinguishes a mistyped schema from an empty one. */
    private static boolean postgresSchemaExists(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /** True if the physical table/view exists in the schema. Distinguishes a typo from a 0-column table. */
    private static boolean postgresRelationExists(Connection c, String schema, String table) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ? "
                + "UNION ALL SELECT 1 FROM information_schema.views WHERE table_schema = ? AND table_name = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema); ps.setString(2, table);
            ps.setString(3, schema); ps.setString(4, table);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static List<Map<String, Object>> postgresTables(Connection c, String schema) throws SQLException {
        // A mistyped/unknown schema must be rejected with a field-specific error, not returned as an
        // empty list (which is indistinguishable from a real but empty schema). DSRC-003-03.
        if (schema != null && !postgresSchemaExists(c, schema)) {
            throw ApiException.notFound("No schema named \"" + schema + "\" exists on this data source.");
        }
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
        // Reject a mistyped/unknown table or schema with a field-specific error instead of returning an
        // empty column list, which a typed identifier would otherwise resolve to silently. DSRC-003-03.
        if (schema != null && !postgresSchemaExists(c, schema)) {
            throw ApiException.notFound("No schema named \"" + schema + "\" exists on this data source.");
        }
        if (table != null && schema != null && !postgresRelationExists(c, schema, table)) {
            throw ApiException.notFound("No table or view named \"" + table + "\" exists in schema \"" + schema + "\".");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = """
                SELECT column_name, data_type, udt_name, character_maximum_length,
                       numeric_precision, numeric_scale, datetime_precision, is_nullable,
                       ordinal_position, column_default, is_identity, is_generated
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
                    col.put("scale", rs.getObject("numeric_scale"));
                    col.put("ordinal", rs.getInt("ordinal_position"));
                    col.put("nullable", "YES".equalsIgnoreCase(rs.getString("is_nullable")));
                    col.put("defaultValue", rs.getString("column_default"));
                    col.put("autoIncrement", "YES".equalsIgnoreCase(rs.getString("is_identity")));
                    String generated = rs.getString("is_generated");
                    col.put("generated", generated != null && !"NEVER".equalsIgnoreCase(generated));
                    out.add(col);
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> postgresForeignKeys(Connection c, String schema, String table) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = """
                SELECT a.attname AS column_name, con.conname AS fk_name, child_cols.ord AS key_seq,
                       ref_ns.nspname AS ref_schema, ref_class.relname AS ref_table,
                       ref_attr.attname AS ref_column, con.confupdtype, con.confdeltype, con.condeferrable
                FROM pg_constraint con
                JOIN pg_class child_class ON child_class.oid = con.conrelid
                JOIN pg_namespace child_ns ON child_ns.oid = child_class.relnamespace
                JOIN pg_class ref_class ON ref_class.oid = con.confrelid
                JOIN pg_namespace ref_ns ON ref_ns.oid = ref_class.relnamespace
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
                    row.put("fkName", rs.getString("fk_name"));
                    row.put("sequence", rs.getInt("key_seq"));
                    row.put("refSchema", rs.getString("ref_schema"));
                    row.put("refTable", rs.getString("ref_table"));
                    row.put("refColumn", rs.getString("ref_column"));
                    row.put("updateRule", rs.getString("confupdtype"));
                    row.put("deleteRule", rs.getString("confdeltype"));
                    row.put("deferrable", rs.getBoolean("condeferrable"));
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

    private static String safeString(ResultSet rs, String column) {
        try { return rs.getString(column); }
        catch (Exception ignored) { return null; }
    }

}
