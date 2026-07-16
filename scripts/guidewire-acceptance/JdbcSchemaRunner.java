import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Infrastructure-only runner for applying empty acceptance schemas. It never inserts test rows. */
public final class JdbcSchemaRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException("Usage: <jdbc-url> <user> <password> <sql-file|--probe>");
        }
        try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2])) {
            if ("--probe".equals(args[3])) {
                probe(connection);
                return;
            }
            if ("--verify-prefix".equals(args[3])) {
                if (args.length < 5) throw new IllegalArgumentException("--verify-prefix requires a table prefix");
                verifyPrefix(connection, args[4], args.length >= 6 ? args[5] : null);
                return;
            }
            if ("--oracle-constraint".equals(args[3])) {
                if (args.length < 5) throw new IllegalArgumentException("--oracle-constraint requires a constraint name");
                inspectOracleConstraint(connection, args[4]);
                return;
            }
            if ("--compare-column".equals(args[3])) {
                if (args.length < 10) throw new IllegalArgumentException(
                        "--compare-column requires sourceSchema sourceTable targetSchema targetTable keyColumn valueColumn");
                compareColumn(connection, args[4], args[5], args[6], args[7], args[8], args[9]);
                return;
            }
            if ("--verify-integrity".equals(args[3])) {
                if (args.length < 6) throw new IllegalArgumentException("--verify-integrity requires schema and table prefix");
                verifyIntegrity(connection, args[4], args[5]);
                return;
            }
            List<String> statements = split(Files.readString(Path.of(args[3])));
            int completed = 0;
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    if (sql.isBlank()) continue;
                    statement.execute(sql);
                    completed++;
                }
            }
            System.out.println("Applied " + completed + " empty-schema statements from " + args[3]);
        }
    }

    private static void probe(Connection connection) throws Exception {
        var meta = connection.getMetaData();
        System.out.println("database=" + meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
        System.out.println("user=" + meta.getUserName());
        try (ResultSet schemas = meta.getSchemas()) {
            int count = 0;
            while (schemas.next()) count++;
            System.out.println("visibleSchemas=" + count);
        }
        if (meta.getDatabaseProductName().toLowerCase().contains("oracle")) {
            try (Statement statement = connection.createStatement();
                 ResultSet privileges = statement.executeQuery(
                         "SELECT privilege FROM session_privs " +
                         "WHERE privilege IN ('CREATE USER','CREATE ANY TABLE','ALTER ANY TABLE','DROP USER') " +
                         "ORDER BY privilege")) {
                StringBuilder values = new StringBuilder();
                while (privileges.next()) {
                    if (!values.isEmpty()) values.append(',');
                    values.append(privileges.getString(1));
                }
                System.out.println("schemaPrivileges=" + (values.isEmpty() ? "none" : values));
            }
        }
    }

    private static void verifyPrefix(Connection connection, String prefix, String requestedSchema) throws Exception {
        var meta = connection.getMetaData();
        int tableCount;
        int foreignKeys;
        if (meta.getDatabaseProductName().toLowerCase().contains("oracle")) {
            try (var tables = connection.prepareStatement("SELECT COUNT(*) FROM user_tables WHERE table_name LIKE ?");
                 var keys = connection.prepareStatement("SELECT COUNT(*) FROM user_constraints WHERE constraint_type='R' AND table_name LIKE ?")) {
                String pattern = prefix.toUpperCase() + "%";
                tables.setString(1, pattern);
                keys.setString(1, pattern);
                try (ResultSet tableRows = tables.executeQuery(); ResultSet keyRows = keys.executeQuery()) {
                    tableRows.next();
                    keyRows.next();
                    tableCount = tableRows.getInt(1);
                    foreignKeys = keyRows.getInt(1);
                }
            }
        } else {
            tableCount = 0;
            foreignKeys = 0;
            try (ResultSet result = meta.getTables(connection.getCatalog(), requestedSchema, "%", new String[]{"TABLE"})) {
                while (result.next()) {
                    String table = result.getString("TABLE_NAME");
                    if (table == null || !table.toLowerCase().startsWith(prefix.toLowerCase())) continue;
                    tableCount++;
                    try (ResultSet keys = meta.getImportedKeys(connection.getCatalog(), result.getString("TABLE_SCHEM"), table)) {
                        while (keys.next()) foreignKeys++;
                    }
                }
            }
        }

        long totalRows = 0;
        int nonEmptyTables = 0;
        try (ResultSet result = meta.getTables(connection.getCatalog(), requestedSchema, "%", new String[]{"TABLE"});
             Statement statement = connection.createStatement()) {
            String quote = meta.getIdentifierQuoteString().trim();
            while (result.next()) {
                String table = result.getString("TABLE_NAME");
                if (table == null || !table.toLowerCase().startsWith(prefix.toLowerCase())) continue;
                String schema = result.getString("TABLE_SCHEM");
                String qualified = (schema == null || schema.isBlank() ? "" : quote(schema, quote) + ".") + quote(table, quote);
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + qualified)) {
                    rows.next();
                    long count = rows.getLong(1);
                    totalRows += count;
                    if (count > 0) nonEmptyTables++;
                }
            }
        }
        System.out.println("prefix=" + prefix + " tables=" + tableCount + " foreignKeys=" + foreignKeys
                + " nonEmptyTables=" + nonEmptyTables + " totalRows=" + totalRows);
    }

    private static void inspectOracleConstraint(Connection connection, String constraintName) throws Exception {
        String sql = "SELECT c.table_name,c.constraint_type,cc.column_name,cc.position "
                + "FROM user_constraints c JOIN user_cons_columns cc "
                + "ON cc.constraint_name=c.constraint_name "
                + "WHERE c.constraint_name=? ORDER BY cc.position";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, constraintName.toUpperCase());
            try (ResultSet rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    System.out.println("constraint=" + constraintName.toUpperCase()
                            + " table=" + rows.getString("table_name")
                            + " type=" + rows.getString("constraint_type")
                            + " column=" + rows.getString("column_name")
                            + " position=" + rows.getInt("position"));
                }
                if (count == 0) System.out.println("constraint=" + constraintName.toUpperCase() + " notFound=true");
            }
        }
    }

    private static void compareColumn(Connection connection, String sourceSchema, String sourceTable,
                                      String targetSchema, String targetTable, String keyColumn,
                                      String valueColumn) throws Exception {
        String quote = connection.getMetaData().getIdentifierQuoteString().trim();
        String source = quote(sourceSchema, quote) + "." + quote(sourceTable, quote);
        String target = quote(targetSchema, quote) + "." + quote(targetTable, quote);
        String key = quote(keyColumn, quote);
        String value = quote(valueColumn, quote);
        String sql = "SELECT COUNT(*), "
                + "SUM(CASE WHEN t." + key + " IS NOT NULL THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN (s." + value + " = t." + value + " OR (s." + value + " IS NULL AND t." + value
                + " IS NULL)) THEN 0 ELSE 1 END) "
                + "FROM " + source + " s LEFT JOIN " + target + " t ON s." + key + " = t." + key;
        long sourceRows;
        long matchedRows;
        long changedRows;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            sourceRows = rows.getLong(1);
            matchedRows = rows.getLong(2);
            changedRows = rows.getLong(3);
        }
        long sourceDistinct = distinctCount(connection, source, value);
        long targetDistinct = distinctCount(connection, target, value);
        System.out.println("compare=" + sourceTable + "." + valueColumn + "->" + targetTable + "." + valueColumn
                + " sourceRows=" + sourceRows + " matchedRows=" + matchedRows + " changedRows=" + changedRows
                + " sourceDistinct=" + sourceDistinct + " targetDistinct=" + targetDistinct);
    }

    private static long distinctCount(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(DISTINCT " + column + ") FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private record ForeignKey(String name, String childSchema, String childTable,
                              String parentSchema, String parentTable,
                              List<String> childColumns, List<String> parentColumns) {}

    private static void verifyIntegrity(Connection connection, String schema, String prefix) throws Exception {
        var metadata = connection.getMetaData();
        String quote = metadata.getIdentifierQuoteString().trim();
        Map<String, MutableForeignKey> grouped = new LinkedHashMap<>();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String table = tables.getString("TABLE_NAME");
                if (table == null || !table.toLowerCase().startsWith(prefix.toLowerCase())) continue;
                String actualSchema = tables.getString("TABLE_SCHEM");
                try (ResultSet keys = metadata.getImportedKeys(connection.getCatalog(), actualSchema, table)) {
                    while (keys.next()) {
                        String fkName = keys.getString("FK_NAME");
                        String id = (fkName == null ? "FK" : fkName) + "|" + keys.getString("FKTABLE_SCHEM")
                                + "|" + keys.getString("FKTABLE_NAME") + "|" + keys.getString("PKTABLE_SCHEM")
                                + "|" + keys.getString("PKTABLE_NAME");
                        MutableForeignKey fk = grouped.get(id);
                        if (fk == null) {
                            fk = new MutableForeignKey(fkName,
                                    keysString(keys, "FKTABLE_SCHEM", actualSchema), keys.getString("FKTABLE_NAME"),
                                    keysString(keys, "PKTABLE_SCHEM", actualSchema), keys.getString("PKTABLE_NAME"));
                            grouped.put(id, fk);
                        }
                        fk.add(keys.getShort("KEY_SEQ"), keys.getString("FKCOLUMN_NAME"), keys.getString("PKCOLUMN_NAME"));
                    }
                }
            }
        }
        long orphans = 0;
        for (MutableForeignKey mutable : grouped.values()) {
            ForeignKey fk = mutable.freeze();
            String child = quote(fk.childSchema(), quote) + "." + quote(fk.childTable(), quote);
            String parent = quote(fk.parentSchema(), quote) + "." + quote(fk.parentTable(), quote);
            List<String> joins = new ArrayList<>();
            List<String> populated = new ArrayList<>();
            for (int i = 0; i < fk.childColumns().size(); i++) {
                joins.add("c." + quote(fk.childColumns().get(i), quote) + " = p." + quote(fk.parentColumns().get(i), quote));
                populated.add("c." + quote(fk.childColumns().get(i), quote) + " IS NOT NULL");
            }
            String sql = "SELECT COUNT(*) FROM " + child + " c LEFT JOIN " + parent + " p ON "
                    + String.join(" AND ", joins) + " WHERE " + String.join(" AND ", populated)
                    + " AND p." + quote(fk.parentColumns().get(0), quote) + " IS NULL";
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                orphans += rows.getLong(1);
            }
        }
        System.out.println("integritySchema=" + schema + " prefix=" + prefix + " relationships=" + grouped.size()
                + " orphanRows=" + orphans);
    }

    private static String keysString(ResultSet keys, String column, String fallback) throws Exception {
        String value = keys.getString(column);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class MutableForeignKey {
        private final String name;
        private final String childSchema;
        private final String childTable;
        private final String parentSchema;
        private final String parentTable;
        private final Map<Short, String> childColumns = new java.util.TreeMap<>();
        private final Map<Short, String> parentColumns = new java.util.TreeMap<>();

        private MutableForeignKey(String name, String childSchema, String childTable,
                                  String parentSchema, String parentTable) {
            this.name = name;
            this.childSchema = childSchema;
            this.childTable = childTable;
            this.parentSchema = parentSchema;
            this.parentTable = parentTable;
        }

        private void add(short sequence, String childColumn, String parentColumn) {
            childColumns.put(sequence, childColumn);
            parentColumns.put(sequence, parentColumn);
        }

        private ForeignKey freeze() {
            return new ForeignKey(name, childSchema, childTable, parentSchema, parentTable,
                    new ArrayList<>(childColumns.values()), new ArrayList<>(parentColumns.values()));
        }
    }

    private static String quote(String identifier, String quote) {
        if (quote == null || quote.isBlank()) return identifier;
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\'' && (i == 0 || script.charAt(i - 1) != '\\')) single = !single;
            if (c == ';' && !single) {
                statements.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) statements.add(current.toString().trim());
        return statements;
    }
}
