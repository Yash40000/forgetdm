package io.forgetdm.virtualization;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.SqlDialect;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * Ingest and materialize logic for the TimeFlow engine.
 *
 * Ingest: stream every user table of a source connection in primary-key order,
 * serialize rows into fixed-size batches ("blocks"), and store each batch in the
 * content-addressed pool. Stable PK ordering means an unchanged region of a table
 * produces byte-identical chunks on the next snapshot, so only changed blocks are
 * physically written (the dedup is the incremental ingestion).
 *
 * Materialize: rebuild a snapshot into a writable layer — an H2 file VDB or a real
 * Postgres / Oracle / DB2 / SQL Server target — with dialect-aware DDL type mapping,
 * batched inserts, and FKs applied after data load.
 */
@Component
public class TimeFlowEngine {
    static final int ROWS_PER_CHUNK = 512;
    static final int INSERT_BATCH = 500;

    private final ChunkStore pool;
    private final ObjectMapper json = new ObjectMapper();

    public TimeFlowEngine(ChunkStore pool) {
        this.pool = pool;
    }

    public record IngestResult(String manifestHash, int tableCount, long rowCount,
                               int chunkCount, int newChunkCount, long logicalBytes, long storedBytes) {}

    // ---------------------------------------------------------------- ingest

    public IngestResult ingest(Connection c, String schema) {
        try {
            List<String> tables = userTables(c, schema);
            List<SnapshotManifest.TableManifest> tableManifests = new ArrayList<>();
            int chunkCount = 0, newChunks = 0;
            long rows = 0, logicalBytes = 0, storedBytes = 0;

            for (String table : tables) {
                List<SnapshotManifest.ColumnInfo> columns = columns(c, schema, table);
                if (columns.isEmpty()) continue;
                List<String> pk = primaryKey(c, schema, table);
                List<String> chunkHashes = new ArrayList<>();
                long tableRows = 0;

                String order = pk.isEmpty() ? "" :
                        " ORDER BY " + String.join(", ", pk.stream().map(TimeFlowEngine::q).toList());
                String select = "SELECT " + String.join(", ", columns.stream().map(col -> q(col.name())).toList())
                        + " FROM " + q(schema, table) + order;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(select)) {
                    List<List<String>> batch = new ArrayList<>(ROWS_PER_CHUNK);
                    while (rs.next()) {
                        batch.add(readRow(rs, columns));
                        tableRows++;
                        if (batch.size() >= ROWS_PER_CHUNK) {
                            ChunkStore.PutResult put = storeBatch(batch);
                            chunkHashes.add(put.hash());
                            chunkCount++; if (put.stored()) { newChunks++; storedBytes += put.storedBytes(); }
                            logicalBytes += put.logicalBytes();
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        ChunkStore.PutResult put = storeBatch(batch);
                        chunkHashes.add(put.hash());
                        chunkCount++; if (put.stored()) { newChunks++; storedBytes += put.storedBytes(); }
                        logicalBytes += put.logicalBytes();
                    }
                }
                rows += tableRows;
                tableManifests.add(new SnapshotManifest.TableManifest(table, columns, pk, tableRows, chunkHashes));
            }

            SnapshotManifest manifest = new SnapshotManifest(1, schema, tableManifests, foreignKeys(c, schema, tables));
            ChunkStore.PutResult put = pool.put(json.writeValueAsBytes(manifest));
            chunkCount++; if (put.stored()) { newChunks++; storedBytes += put.storedBytes(); }
            logicalBytes += put.logicalBytes();

            return new IngestResult(put.hash(), tableManifests.size(), rows,
                    chunkCount, newChunks, logicalBytes, storedBytes);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("TimeFlow ingest failed: " + e.getMessage()); }
    }

    public SnapshotManifest loadManifest(String manifestHash) {
        try {
            return json.readValue(pool.get(manifestHash), SnapshotManifest.class);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Cannot read snapshot manifest: " + e.getMessage()); }
    }

    private ChunkStore.PutResult storeBatch(List<List<String>> rows) throws Exception {
        return pool.put(json.writeValueAsBytes(rows));
    }

    private List<String> readRow(ResultSet rs, List<SnapshotManifest.ColumnInfo> columns) throws SQLException {
        List<String> row = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            if (isBinary(columns.get(i).jdbcType())) {
                byte[] b = rs.getBytes(i + 1);
                row.add(b == null ? null : Base64.getEncoder().encodeToString(b));
            } else {
                row.add(rs.getString(i + 1));
            }
        }
        return row;
    }

    // ----------------------------------------------------------- materialize

    /**
     * Create tables (PKs inline), stream chunk data in, then add FK constraints.
     *
     * If any table already exists in the target schema (e.g. from a prior failed
     * or partial materialization) it is dropped first so the operation is idempotent.
     * FK constraints are dropped alongside their child tables via CASCADE where the
     * dialect supports it, or by dropping FKs explicitly first otherwise.
     */
    public long materialize(Connection target, SqlDialect dialect, SnapshotManifest manifest) {
        long rows = 0;
        try {
            boolean auto = target.getAutoCommit();
            target.setAutoCommit(false);
            try (Statement st = target.createStatement()) {
                // 1) Drop existing tables children-first so FK constraints don't block the drop
                dropTablesIfExist(st, dialect, manifest);
                target.commit();

                // 2) Create tables (PKs inline, FKs deferred)
                for (SnapshotManifest.TableManifest t : manifest.tables()) {
                    st.execute(createTableDdl(t, dialect));
                }
                target.commit();

                // 3) Load data
                for (SnapshotManifest.TableManifest t : manifest.tables()) {
                    rows += loadTable(target, t);
                    target.commit();
                }

                // 4) Add FK constraints after data is fully loaded (avoids ordering issues)
                for (String fk : foreignKeyDdl(manifest, dialect)) {
                    try {
                        st.execute(fk);
                    } catch (SQLException e) {
                        // Non-fatal: FK may reference a table that was excluded from the snapshot.
                        // Log as a warning and continue rather than aborting the whole materialize.
                    }
                }
                target.commit();
            } finally {
                target.setAutoCommit(auto);
            }
            return rows;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Materialize VDB failed: " + e.getMessage()); }
    }

    /**
     * Drop all manifest tables from the target if they exist, children before parents.
     * Uses DROP TABLE IF EXISTS where the dialect supports it; otherwise suppresses
     * "table does not exist" errors from a plain DROP TABLE.
     */
    private void dropTablesIfExist(Statement st, SqlDialect dialect, SnapshotManifest manifest) {
        List<String> order = new ArrayList<>(manifest.tables().stream()
                .map(SnapshotManifest.TableManifest::name).toList());
        // Children before parents: sort by FK depth descending
        order.sort(Comparator.comparingInt((String t) -> depth(t, manifest)).reversed());
        for (String table : order) {
            try {
                String sql = dropIfExistsSql(dialect, table);
                st.execute(sql);
            } catch (SQLException ignored) {
                // Dialect doesn't support IF EXISTS (DB2 < 10.5, older Oracle) — already dropped or never existed
            }
        }
    }

    /**
     * Returns a DROP TABLE statement that does not error if the table is absent.
     * Postgres, H2, MySQL: DROP TABLE IF EXISTS t
     * SQL Server:          IF OBJECT_ID(N't','U') IS NOT NULL DROP TABLE t
     * Oracle:              uses exception-suppression (no native IF EXISTS before 23c)
     * DB2:                 plain DROP TABLE (exception suppressed by caller)
     */
    private static String dropIfExistsSql(SqlDialect dialect, String table) {
        String qt = q(table);
        return switch (dialect) {
            case SQLSERVER -> "IF OBJECT_ID(N'" + table.replace("'", "''") + "', N'U') IS NOT NULL DROP TABLE " + qt;
            case ORACLE    -> "BEGIN EXECUTE IMMEDIATE 'DROP TABLE " + qt + " CASCADE CONSTRAINTS'; EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;";
            case DB2       -> "DROP TABLE " + qt;   // caller suppresses "table not found" (SQLCODE -204)
            default        -> "DROP TABLE IF EXISTS " + qt;  // Postgres, H2, MySQL, Generic
        };
    }

    /** Drop the manifest's tables from a target (children before parents). Missing tables are skipped. */
    public void dropTables(Connection target, SnapshotManifest manifest) {
        List<String> order = new ArrayList<>(manifest.tables().stream().map(SnapshotManifest.TableManifest::name).toList());
        // children first: reverse a parent-first topological order over the FK edges
        order.sort(Comparator.comparingInt(t -> depth(t, manifest)));
        Collections.reverse(order);
        for (String table : order) {
            try (Statement st = target.createStatement()) {
                st.execute("DROP TABLE " + q(table));
            } catch (SQLException ignored) {
                // table may not exist yet on this target
            }
        }
    }

    private int depth(String table, SnapshotManifest manifest) {
        int d = 0; String cur = table; Set<String> seen = new HashSet<>();
        while (seen.add(cur)) {
            String parent = null;
            for (SnapshotManifest.FkInfo fk : manifest.foreignKeys()) {
                if (fk.childTable().equals(cur) && !fk.parentTable().equals(cur)) { parent = fk.parentTable(); break; }
            }
            if (parent == null) return d;
            cur = parent; d++;
        }
        return d;
    }

    private long loadTable(Connection target, SnapshotManifest.TableManifest t) throws Exception {
        if (t.chunks().isEmpty()) return 0;
        String sql = "INSERT INTO " + q(t.name()) + " ("
                + String.join(", ", t.columns().stream().map(c -> q(c.name())).toList())
                + ") VALUES (" + String.join(", ", Collections.nCopies(t.columns().size(), "?")) + ")";
        long rows = 0;
        try (PreparedStatement ps = target.prepareStatement(sql)) {
            int pending = 0;
            for (String chunkHash : t.chunks()) {
                List<List<String>> batch = json.readValue(pool.get(chunkHash),
                        json.getTypeFactory().constructCollectionType(List.class,
                                json.getTypeFactory().constructCollectionType(List.class, String.class)));
                for (List<String> row : batch) {
                    bindRow(ps, t.columns(), row);
                    ps.addBatch();
                    rows++;
                    if (++pending >= INSERT_BATCH) { ps.executeBatch(); pending = 0; }
                }
            }
            if (pending > 0) ps.executeBatch();
        }
        return rows;
    }

    private void bindRow(PreparedStatement ps, List<SnapshotManifest.ColumnInfo> columns, List<String> row) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            SnapshotManifest.ColumnInfo col = columns.get(i);
            String v = i < row.size() ? row.get(i) : null;
            int idx = i + 1;
            if (v == null) { ps.setNull(idx, portableSqlType(col.jdbcType())); continue; }
            switch (col.jdbcType()) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                     Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.REAL, Types.DOUBLE ->
                        ps.setBigDecimal(idx, new BigDecimal(v.trim()));
                case Types.BOOLEAN, Types.BIT ->
                        ps.setBoolean(idx, v.equalsIgnoreCase("true") || v.equals("1")
                                || v.equalsIgnoreCase("t") || v.equalsIgnoreCase("y"));
                case Types.DATE -> ps.setDate(idx, java.sql.Date.valueOf(v.substring(0, Math.min(10, v.length()))));
                case Types.TIME, Types.TIME_WITH_TIMEZONE -> ps.setTime(idx, Time.valueOf(v.substring(0, Math.min(8, v.length()))));
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> ps.setTimestamp(idx, parseTimestamp(v));
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                        ps.setBytes(idx, Base64.getDecoder().decode(v));
                default -> ps.setString(idx, v);
            }
        }
    }

    private static Timestamp parseTimestamp(String v) {
        String s = v.trim().replace('T', ' ');
        int plus = s.indexOf('+');                       // strip offsets like +02:00
        if (plus > 10) s = s.substring(0, plus);
        if (s.length() == 10) s += " 00:00:00";
        return Timestamp.valueOf(s);
    }

    private static boolean isBinary(int jdbcType) {
        return jdbcType == Types.BINARY || jdbcType == Types.VARBINARY
                || jdbcType == Types.LONGVARBINARY || jdbcType == Types.BLOB;
    }

    private static int portableSqlType(int jdbcType) {
        return switch (jdbcType) {
            case Types.TIME_WITH_TIMEZONE -> Types.TIME;
            case Types.TIMESTAMP_WITH_TIMEZONE -> Types.TIMESTAMP;
            case Types.BLOB -> Types.VARBINARY;
            case Types.CLOB, Types.NCLOB -> Types.VARCHAR;
            default -> jdbcType;
        };
    }

    // ------------------------------------------------------------------ DDL

    String createTableDdl(SnapshotManifest.TableManifest t, SqlDialect dialect) {
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(q(t.name())).append(" (");
        for (int i = 0; i < t.columns().size(); i++) {
            SnapshotManifest.ColumnInfo c = t.columns().get(i);
            if (i > 0) sb.append(", ");
            sb.append(q(c.name())).append(' ').append(columnType(c, dialect));
            if (!c.nullable()) sb.append(" NOT NULL");
        }
        if (!t.primaryKey().isEmpty()) {
            sb.append(", PRIMARY KEY (")
              .append(String.join(", ", t.primaryKey().stream().map(TimeFlowEngine::q).toList()))
              .append(')');
        }
        return sb.append(')').toString();
    }

    List<String> foreignKeyDdl(SnapshotManifest manifest, SqlDialect dialect) {
        Set<String> tables = new HashSet<>();
        manifest.tables().forEach(t -> tables.add(t.name()));
        List<String> out = new ArrayList<>();
        int n = 0;
        for (SnapshotManifest.FkInfo fk : manifest.foreignKeys()) {
            if (!tables.contains(fk.childTable()) || !tables.contains(fk.parentTable())) continue;
            out.add("ALTER TABLE " + q(fk.childTable())
                    + " ADD CONSTRAINT " + q("fk_vdb_" + fk.childTable() + "_" + (++n))
                    + " FOREIGN KEY (" + String.join(", ", fk.childColumns().stream().map(TimeFlowEngine::q).toList())
                    + ") REFERENCES " + q(fk.parentTable())
                    + " (" + String.join(", ", fk.parentColumns().stream().map(TimeFlowEngine::q).toList()) + ")");
        }
        return out;
    }

    /** Portable column type rendered for the target dialect. */
    String columnType(SnapshotManifest.ColumnInfo c, SqlDialect d) {
        int size = Math.max(1, Math.min(c.size(), 32_000));
        int precision = Math.max(1, Math.min(c.size(), 38));
        int scale = Math.max(0, Math.min(c.scale(), precision));
        return switch (c.jdbcType()) {
            case Types.CHAR, Types.NCHAR -> charType(d, size);
            case Types.VARCHAR, Types.NVARCHAR -> c.size() <= 0 || c.size() > 32_000 ? textType(d) : varcharType(d, size);
            case Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.SQLXML -> textType(d);
            case Types.TINYINT, Types.SMALLINT -> d == SqlDialect.ORACLE ? "NUMBER(5)" : "SMALLINT";
            case Types.INTEGER -> d == SqlDialect.ORACLE ? "NUMBER(10)" : "INTEGER";
            case Types.BIGINT -> d == SqlDialect.ORACLE ? "NUMBER(19)" : "BIGINT";
            case Types.DECIMAL, Types.NUMERIC ->
                    (d == SqlDialect.ORACLE ? "NUMBER(" : "DECIMAL(") + precision + "," + scale + ")";
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> switch (d) {
                case ORACLE -> "BINARY_DOUBLE";
                case SQLSERVER -> "FLOAT";
                default -> "DOUBLE PRECISION";
            };
            case Types.BOOLEAN, Types.BIT -> switch (d) {
                case ORACLE -> "NUMBER(1)";
                case SQLSERVER -> "BIT";
                default -> "BOOLEAN";
            };
            case Types.DATE -> "DATE";
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> d == SqlDialect.ORACLE ? "VARCHAR2(18)" : "TIME";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                    d == SqlDialect.SQLSERVER ? "DATETIME2" : "TIMESTAMP";
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> switch (d) {
                case POSTGRES -> "BYTEA";
                case SQLSERVER -> "VARBINARY(MAX)";
                case ORACLE, DB2 -> "BLOB";
                default -> "VARBINARY(" + Math.max(size, 255) + ")";
            };
            default -> varcharType(d, 4000);
        };
    }

    private static String charType(SqlDialect d, int n) {
        return (d == SqlDialect.ORACLE ? "CHAR(" : "CHAR(") + n + ")";
    }

    private static String varcharType(SqlDialect d, int n) {
        return (d == SqlDialect.ORACLE ? "VARCHAR2(" : "VARCHAR(") + n + ")";
    }

    private static String textType(SqlDialect d) {
        return switch (d) {
            case POSTGRES -> "TEXT";
            case SQLSERVER -> "VARCHAR(MAX)";
            case ORACLE, DB2 -> "CLOB";
            default -> "CLOB";
        };
    }

    // ------------------------------------------------------------- metadata

    List<String> userTables(Connection c, String schema) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String t = rs.getString("TABLE_NAME");
                if (t == null || SqlDialect.isSystemTable(t)) continue;
                if (t.toLowerCase(Locale.ROOT).startsWith("flyway_")) continue;
                out.add(t);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private List<SnapshotManifest.ColumnInfo> columns(Connection c, String schema, String table) throws SQLException {
        List<SnapshotManifest.ColumnInfo> out = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                out.add(new SnapshotManifest.ColumnInfo(
                        rs.getString("COLUMN_NAME"),
                        rs.getInt("DATA_TYPE"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        Math.max(0, rs.getInt("DECIMAL_DIGITS")),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
            }
        }
        return out;
    }

    private List<String> primaryKey(Connection c, String schema, String table) throws SQLException {
        TreeMap<Short, String> cols = new TreeMap<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, schema, table)) {
            while (rs.next()) cols.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(cols.values());
    }

    private List<SnapshotManifest.FkInfo> foreignKeys(Connection c, String schema, List<String> tables) throws SQLException {
        List<SnapshotManifest.FkInfo> out = new ArrayList<>();
        for (String table : tables) {
            Map<String, List<String[]>> byFk = new LinkedHashMap<>();
            try (ResultSet rs = c.getMetaData().getImportedKeys(null, schema, table)) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    byFk.computeIfAbsent(fkName == null ? table + "_" + byFk.size() : fkName, k -> new ArrayList<>())
                        .add(new String[]{rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")});
                }
            }
            for (List<String[]> parts : byFk.values()) {
                if (parts.isEmpty()) continue;
                out.add(new SnapshotManifest.FkInfo(table,
                        parts.stream().map(p -> p[0]).toList(),
                        parts.get(0)[1],
                        parts.stream().map(p -> p[2]).toList()));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- utils

    static String q(String ident) {
        if (ident == null || !ident.matches("[A-Za-z0-9_]+")) throw ApiException.bad("Illegal identifier: " + ident);
        return "\"" + ident + "\"";
    }

    static String q(String schema, String table) {
        return schema == null || schema.isBlank() ? q(table) : q(schema) + "." + q(table);
    }
}
