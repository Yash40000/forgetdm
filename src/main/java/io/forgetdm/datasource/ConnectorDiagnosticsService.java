package io.forgetdm.datasource;

import io.forgetdm.common.ApiException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs a bounded, read-only preflight over a real schema before discovery or provisioning. */
@Service
public class ConnectorDiagnosticsService {
    private static final int DEFAULT_TABLE_LIMIT = 500;
    private static final int MAX_TABLE_LIMIT = 2_000;
    private static final Set<Integer> LOB_TYPES = Set.of(Types.BLOB, Types.CLOB, Types.NCLOB,
            Types.LONGVARBINARY, Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.SQLXML);
    private static final Set<Integer> COMPLEX_TYPES = Set.of(Types.ARRAY, Types.STRUCT, Types.REF,
            Types.REF_CURSOR, Types.JAVA_OBJECT, Types.DATALINK, Types.OTHER);
    private static final Set<String> ANSI_WORDS = Set.of("select", "table", "column", "user", "group", "order",
            "where", "from", "to", "by", "constraint", "primary", "foreign", "references", "check", "index");

    private final ConnectionFactory connections;

    public ConnectorDiagnosticsService(ConnectionFactory connections) {
        this.connections = connections;
    }

    public Report inspect(DataSourceEntity source, String requestedSchema, Integer requestedLimit) {
        int limit = Math.min(MAX_TABLE_LIMIT, Math.max(1, requestedLimit == null ? DEFAULT_TABLE_LIMIT : requestedLimit));
        try (Connection connection = connections.open(source)) {
            DatabaseMetaData md = connection.getMetaData();
            SqlDialect dialect = SqlDialect.fromConnection(connection);
            String schema = DataSourceService.normalizeSchema(connection, requestedSchema);
            String catalog = safe(connection::getCatalog);
            if (dialect == SqlDialect.MYSQL) {
                if (requestedSchema != null && !requestedSchema.isBlank() && !"__default__".equals(requestedSchema))
                    catalog = requestedSchema;
                schema = null;
            }
            Set<String> reserved = reservedWords(md);
            List<String> allTables = tableNames(md, catalog, schema);
            List<String> scannedTables = allTables.subList(0, Math.min(limit, allTables.size()));

            int columnCount = 0, lobColumns = 0, complexColumns = 0, generatedColumns = 0;
            int quotedIdentifiers = 0, compositePrimaryKeys = 0, tablesWithoutPrimaryKey = 0;
            int compositeForeignKeys = 0, selfReferences = 0;
            Map<String, Set<String>> graph = new LinkedHashMap<>();
            Map<String, String> foldedNames = new HashMap<>();
            Set<String> caseCollisions = new LinkedHashSet<>();
            Set<String> complexTypeNames = new LinkedHashSet<>();
            List<Map<String, Object>> tableEvidence = new ArrayList<>();

            for (String table : scannedTables) {
                graph.computeIfAbsent(table, ignored -> new LinkedHashSet<>());
                String prior = foldedNames.putIfAbsent(table.toLowerCase(Locale.ROOT), table);
                if (prior != null && !prior.equals(table)) caseCollisions.add(prior + " / " + table);
                if (requiresQuoting(table, reserved)) quotedIdentifiers++;

                int tableColumns = 0, tableLobs = 0, tableComplex = 0;
                try (ResultSet columns = md.getColumns(catalog, schema, table, "%")) {
                    while (columns.next()) {
                        tableColumns++;
                        columnCount++;
                        String column = columns.getString("COLUMN_NAME");
                        int jdbcType = columns.getInt("DATA_TYPE");
                        String typeName = columns.getString("TYPE_NAME");
                        if (requiresQuoting(column, reserved)) quotedIdentifiers++;
                        if (LOB_TYPES.contains(jdbcType)) { lobColumns++; tableLobs++; }
                        if (COMPLEX_TYPES.contains(jdbcType)) {
                            complexColumns++; tableComplex++;
                            if (typeName != null) complexTypeNames.add(typeName);
                        }
                        if ("YES".equalsIgnoreCase(safeColumn(columns, "IS_AUTOINCREMENT"))
                                || "YES".equalsIgnoreCase(safeColumn(columns, "IS_GENERATEDCOLUMN"))) generatedColumns++;
                    }
                }

                List<String> primaryKey = new ArrayList<>();
                try (ResultSet keys = md.getPrimaryKeys(catalog, schema, table)) {
                    while (keys.next()) primaryKey.add(keys.getString("COLUMN_NAME"));
                }
                if (primaryKey.isEmpty()) tablesWithoutPrimaryKey++;
                else if (primaryKey.size() > 1) compositePrimaryKeys++;

                Map<String, Integer> fkWidth = new LinkedHashMap<>();
                try (ResultSet keys = md.getImportedKeys(catalog, schema, table)) {
                    while (keys.next()) {
                        String parent = keys.getString("PKTABLE_NAME");
                        String fkName = safeColumn(keys, "FK_NAME");
                        String group = fkName == null || fkName.isBlank()
                                ? table + "->" + parent + ":" + safeColumn(keys, "PK_NAME")
                                : fkName;
                        fkWidth.merge(group, 1, Integer::sum);
                        if (parent != null) graph.get(table).add(parent);
                        if (table.equalsIgnoreCase(parent)) selfReferences++;
                    }
                }
                compositeForeignKeys += (int) fkWidth.values().stream().filter(width -> width > 1).count();
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("table", table);
                evidence.put("columns", tableColumns);
                evidence.put("primaryKeyColumns", primaryKey.size());
                evidence.put("foreignKeys", fkWidth.size());
                evidence.put("lobColumns", tableLobs);
                evidence.put("complexColumns", tableComplex);
                tableEvidence.add(evidence);
            }

            long cycleTables = graph.keySet().stream().filter(table -> participatesInCycle(table, graph)).count();
            int partitionedTables = partitionedTableCount(connection, dialect, schema);
            Map<String, Object> connectionInfo = connectionInfo(md, source, dialect);
            Map<String, Object> capabilities = capabilities(md, dialect);
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("schema", dialect == SqlDialect.MYSQL ? catalog : schema);
            shape.put("tablesFound", allTables.size());
            shape.put("tablesScanned", scannedTables.size());
            shape.put("truncated", allTables.size() > scannedTables.size());
            shape.put("columns", columnCount);
            shape.put("tablesWithoutPrimaryKey", tablesWithoutPrimaryKey);
            shape.put("compositePrimaryKeys", compositePrimaryKeys);
            shape.put("compositeForeignKeys", compositeForeignKeys);
            shape.put("selfReferences", selfReferences);
            shape.put("cycleTables", cycleTables);
            shape.put("lobColumns", lobColumns);
            shape.put("complexColumns", complexColumns);
            shape.put("complexTypeNames", complexTypeNames);
            shape.put("generatedColumns", generatedColumns);
            shape.put("quotedIdentifiers", quotedIdentifiers);
            shape.put("caseInsensitiveNameCollisions", caseCollisions);
            shape.put("partitionedTables", partitionedTables < 0 ? null : partitionedTables);
            shape.put("partitionInspection", partitionedTables < 0 ? "not available for this engine/privilege" : "catalog verified");
            shape.put("encoding", encodingInfo(connection, dialect));
            shape.put("tables", tableEvidence);

            List<Issue> issues = issues(source, dialect, md, shape, allTables.size(), scannedTables.size());
            int score = readinessScore(issues);
            return new Report(score, score >= 85 ? "READY" : score >= 65 ? "REVIEW" : "BLOCKED",
                    connectionInfo, capabilities, shape, issues, Instant.now());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Connector diagnostics failed for '" + source.getName() + "': " + rootMessage(e));
        }
    }

    private static Map<String, Object> connectionInfo(DatabaseMetaData md, DataSourceEntity source, SqlDialect dialect) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", source.getName());
        out.put("configuredKind", source.getKind());
        out.put("detectedDialect", dialect.name());
        out.put("product", md.getDatabaseProductName());
        out.put("productVersion", md.getDatabaseProductVersion());
        out.put("driver", md.getDriverName());
        out.put("driverVersion", md.getDriverVersion());
        out.put("jdbcVersion", md.getJDBCMajorVersion() + "." + md.getJDBCMinorVersion());
        out.put("identifierQuote", md.getIdentifierQuoteString());
        out.put("connectorMode", connectorMode(source));
        return out;
    }

    private static Map<String, Object> capabilities(DatabaseMetaData md, SqlDialect dialect) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactions", md.supportsTransactions());
        out.put("batchUpdates", md.supportsBatchUpdates());
        out.put("savepoints", md.supportsSavepoints());
        out.put("schemasInDml", md.supportsSchemasInDataManipulation());
        out.put("catalogsInDml", md.supportsCatalogsInDataManipulation());
        out.put("mixedCaseQuotedIdentifiers", md.supportsMixedCaseQuotedIdentifiers());
        out.put("ddlCommitsTransaction", md.dataDefinitionCausesTransactionCommit());
        out.put("maxColumnsInTable", positiveOrNull(md.getMaxColumnsInTable()));
        out.put("maxTableNameLength", positiveOrNull(md.getMaxTableNameLength()));
        out.put("bindParameterLimit", dialect.bindParamLimit());
        out.put("multiRowInsert", dialect.supportsMultiRowInsert());
        out.put("nativeDialect", dialect != SqlDialect.GENERIC);
        out.put("lobStreaming", true);
        return out;
    }

    private static List<Issue> issues(DataSourceEntity source, SqlDialect dialect, DatabaseMetaData md,
                                      Map<String, Object> shape, int found, int scanned) throws SQLException {
        List<Issue> out = new ArrayList<>();
        int noPk = number(shape.get("tablesWithoutPrimaryKey"));
        int cycles = number(shape.get("cycleTables"));
        int lobs = number(shape.get("lobColumns"));
        int complex = number(shape.get("complexColumns"));
        int quoted = number(shape.get("quotedIdentifiers"));
        int collisions = ((Set<?>) shape.get("caseInsensitiveNameCollisions")).size();
        if (found > scanned) out.add(new Issue("WARN", "SCAN_TRUNCATED", "Schema scan was bounded",
                "Scanned " + scanned + " of " + found + " tables.", "Re-run with a larger maxTables value or diagnose one schema at a time."));
        if (noPk > 0) out.add(new Issue("HIGH", "MISSING_PRIMARY_KEYS", noPk + " tables have no declared primary key",
                "Stable update, subset, and restart semantics need an explicit business key.", "Define custom keys in DataScope before provisioning these tables."));
        if (cycles > 0) out.add(new Issue("WARN", "CYCLIC_RELATIONSHIPS", cycles + " tables participate in FK cycles",
                "Load order alone cannot satisfy a circular graph.", "Use deferred constraints, staged key remapping, or the two-phase relationship loader."));
        if (lobs > 0) out.add(new Issue("INFO", "LARGE_OBJECTS", lobs + " LOB/long-value columns detected",
                "ForgeTDM streams LOB binds, but masking a LOB may still require content-aware rules.", "Run a sampled LOB validation and set an explicit pass-through or masking rule."));
        if (complex > 0) out.add(new Issue("WARN", "VENDOR_TYPES", complex + " vendor/complex columns detected",
                "ARRAY, STRUCT, REF, OTHER, and object types are not safely portable across engines.", "Add a vendor adapter or exclude/flatten these columns in the table map."));
        if (quoted > 0) out.add(new Issue("INFO", "QUOTED_IDENTIFIERS", quoted + " identifiers require careful quoting",
                "Spaces, punctuation, or reserved words were found.", "Keep exact catalog casing; do not normalize these names in mappings."));
        if (collisions > 0) out.add(new Issue("HIGH", "CASE_COLLISIONS", collisions + " case-insensitive name collisions detected",
                "The target engine may fold two source objects to the same identifier.", "Rename or explicitly map colliding objects before cross-engine provisioning."));
        if (!md.supportsTransactions() && allowsTarget(source)) out.add(new Issue("HIGH", "NO_TRANSACTIONS", "Driver reports no transaction support",
                "A failed target load cannot be atomically rolled back.", "Use a native loader with staging/swap or a transactional target."));
        if (!md.supportsBatchUpdates()) out.add(new Issue("WARN", "NO_JDBC_BATCH", "Driver reports no JDBC batch support",
                "Portable loads will be substantially slower.", "Install the vendor native loader or a newer JDBC driver."));
        if (dialect == SqlDialect.GENERIC) out.add(new Issue("WARN", "GENERIC_DIALECT", "Generic SQL compatibility mode",
                "Connection works, but vendor DDL, truncate, constraint, and bulk-load behavior is not specialized.", "Run this report in the customer environment and add a tested dialect adapter before production use."));
        return out;
    }

    private static List<String> tableNames(DatabaseMetaData md, String catalog, String schema) throws SQLException {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (ResultSet tables = md.getTables(catalog, schema, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                String rowSchema = safeColumn(tables, "TABLE_SCHEM");
                if (name == null || SqlDialect.isSystemTable(name)
                        || (rowSchema != null && SqlDialect.isSystemSchema(rowSchema)) || !seen.add(name)) continue;
                out.add(name);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static Set<String> reservedWords(DatabaseMetaData md) throws SQLException {
        Set<String> out = new HashSet<>(ANSI_WORDS);
        String words = md.getSQLKeywords();
        if (words != null) Arrays.stream(words.split(",")).map(String::trim).filter(word -> !word.isEmpty())
                .map(word -> word.toLowerCase(Locale.ROOT)).forEach(out::add);
        return out;
    }

    private static boolean requiresQuoting(String name, Set<String> reserved) {
        return name != null && (!name.matches("[A-Za-z_][A-Za-z0-9_$#]*") || reserved.contains(name.toLowerCase(Locale.ROOT)));
    }

    private static boolean participatesInCycle(String start, Map<String, Set<String>> graph) {
        return reaches(start, start, graph, new HashSet<>(), true);
    }

    private static boolean reaches(String start, String current, Map<String, Set<String>> graph, Set<String> visited, boolean root) {
        if (!root && start.equalsIgnoreCase(current)) return true;
        if (!visited.add(current.toLowerCase(Locale.ROOT))) return false;
        for (String next : graph.getOrDefault(current, Set.of()))
            if (reaches(start, next, graph, new HashSet<>(visited), false)) return true;
        return false;
    }

    private static int partitionedTableCount(Connection connection, SqlDialect dialect, String schema) {
        String sql = switch (dialect) {
            case POSTGRES -> "SELECT COUNT(*) FROM pg_partitioned_table p JOIN pg_class c ON c.oid=p.partrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=?";
            case ORACLE -> "SELECT COUNT(*) FROM all_part_tables WHERE owner=?";
            case SQLSERVER -> "SELECT COUNT(DISTINCT t.object_id) FROM sys.tables t JOIN sys.indexes i ON i.object_id=t.object_id JOIN sys.partition_schemes p ON p.data_space_id=i.data_space_id JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=?";
            case MYSQL -> "SELECT COUNT(DISTINCT table_name) FROM information_schema.partitions WHERE table_schema=? AND partition_name IS NOT NULL";
            case DB2 -> "SELECT COUNT(*) FROM syscat.tables WHERE tabschema=? AND partition_mode <> 'N'";
            default -> null;
        };
        if (sql == null || schema == null) return -1;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, dialect == SqlDialect.ORACLE || dialect == SqlDialect.DB2 ? schema.toUpperCase(Locale.ROOT) : schema);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (Exception ignored) { return -1; }
    }

    private static Map<String, String> encodingInfo(Connection connection, SqlDialect dialect) {
        String sql = switch (dialect) {
            case POSTGRES -> "SELECT current_setting('server_encoding'), current_setting('lc_collate')";
            case MYSQL -> "SELECT @@character_set_database, @@collation_database";
            case SQLSERVER -> "SELECT CONVERT(varchar(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation')), 'Unicode strings via JDBC'";
            case ORACLE -> "SELECT MAX(CASE WHEN parameter='NLS_CHARACTERSET' THEN value END), MAX(CASE WHEN parameter='NLS_NCHAR_CHARACTERSET' THEN value END) FROM nls_database_parameters";
            default -> null;
        };
        Map<String, String> out = new LinkedHashMap<>();
        if (sql == null) { out.put("status", "Inspect with vendor catalog privileges"); return out; }
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                out.put("characterSet", String.valueOf(rs.getString(1)));
                out.put("collationOrNationalSet", String.valueOf(rs.getString(2)));
            }
        } catch (Exception e) { out.put("status", "Unavailable: " + rootMessage(e)); }
        return out;
    }

    private static String connectorMode(DataSourceEntity source) {
        String kind = String.valueOf(source.getKind()).toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "POSTGRES", "POSTGRESQL", "MYSQL", "MARIADB", "H2", "DB2", "DB2UDB", "DB2ZOS", "ORACLE", "SQLSERVER" -> "BUNDLED_JDBC";
            case "TERADATA", "SYBASE", "SAP_HANA", "SNOWFLAKE", "BIGQUERY", "REDSHIFT" -> "OPTIONAL_DRIVER_PLUGIN";
            default -> "GENERIC_JDBC";
        };
    }

    private static boolean allowsTarget(DataSourceEntity source) {
        String role = String.valueOf(source.getRole()).toUpperCase(Locale.ROOT);
        return role.equals("TARGET") || role.equals("BOTH");
    }

    private static int readinessScore(List<Issue> issues) {
        int score = 100;
        for (Issue issue : issues) score -= switch (issue.severity()) { case "HIGH" -> 18; case "WARN" -> 8; default -> 1; };
        return Math.max(0, score);
    }

    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private static Integer positiveOrNull(int value) { return value <= 0 ? null : value; }
    private static String safeColumn(ResultSet rs, String name) { try { return rs.getString(name); } catch (Exception ignored) { return null; } }
    private static <T> T safe(SqlSupplier<T> supplier) { try { return supplier.get(); } catch (Exception ignored) { return null; } }
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface private interface SqlSupplier<T> { T get() throws SQLException; }
    public record Issue(String severity, String code, String title, String detail, String remediation) { }
    public record Report(int readinessScore, String status, Map<String, Object> connection,
                         Map<String, Object> capabilities, Map<String, Object> schemaShape,
                         List<Issue> issues, Instant inspectedAt) { }
}
