package io.forgetdm.cdc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.SqlDialect;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Applies buffered CDC changes to a target as an incremental refresh — the concrete
 * "sync only altered rows daily" payoff. Changes are first netted per primary key
 * (last-write-wins; a trailing delete cancels earlier inserts/updates) so the target
 * receives the minimum set of UPSERT/DELETE statements rather than a full reload.
 *
 * Target support today: PostgreSQL (INSERT ... ON CONFLICT). Other engines return a
 * clear "not yet supported" error rather than a partial apply.
 */
@Component
public class CdcIncrementalApplier {

    private final ObjectMapper json;

    public CdcIncrementalApplier(ObjectMapper json) { this.json = json; }

    public record ApplyResult(int upserts, int deletes, int skippedNoPk, int tables, long consumedThroughId) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("upserts", upserts);
            m.put("deletes", deletes);
            m.put("skippedNoPk", skippedNoPk);
            m.put("tables", tables);
            m.put("consumedThroughId", consumedThroughId);
            return m;
        }
    }

    private static final class NetRow {
        String schema, table, op;
        Map<String, String> pk;
        Map<String, String> values;
    }

    public ApplyResult apply(DataSourceEntity target, List<CdcChangeEntity> orderedChanges) {
        if (!isPostgres(target)) {
            throw ApiException.bad("CDC incremental apply currently supports a PostgreSQL target only. '"
                    + target.getName() + "' is not PostgreSQL.");
        }
        try (Connection c = openTarget(target)) {
            return apply(c, SqlDialect.POSTGRES, orderedChanges);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("CDC incremental apply failed: " + rootMessage(e));
        }
    }

    /**
     * Apply to an already-open connection. TimeFlow uses this overload to replay a bounded
     * change set into its isolated H2 workspace before writing an immutable snapshot.
     */
    public ApplyResult apply(Connection c, SqlDialect dialect, List<CdcChangeEntity> orderedChanges) {
        if (dialect != SqlDialect.POSTGRES && dialect != SqlDialect.H2) {
            throw ApiException.bad("CDC replay supports PostgreSQL and the TimeFlow H2 workspace only.");
        }
        // Net the change stream per table+PK. Insertion order is preserved for a stable replay.
        // Merge successive updates so an INSERT followed by a partial UPDATE does not lose values.
        Map<String, Map<String, NetRow>> net = new LinkedHashMap<>();
        long consumedThroughId = 0;
        int skippedNoPk = 0;
        for (CdcChangeEntity ch : orderedChanges) {
            consumedThroughId = Math.max(consumedThroughId, ch.getId() == null ? 0 : ch.getId());
            Map<String, String> pk = readMap(ch.getPkJson());
            Map<String, String> values = readMap(ch.getChangeJson());
            if (pk.isEmpty()) {
                // No primary key on this change — cannot net or upsert safely; count and skip.
                skippedNoPk++;
                continue;
            }
            String tk = tableKey(ch.getSchemaName(), ch.getTableName());
            String pkKey = writeJson(pk);
            Map<String, NetRow> tableRows = net.computeIfAbsent(tk, k -> new LinkedHashMap<>());
            NetRow prior = tableRows.get(pkKey);
            NetRow row = new NetRow();
            row.schema = ch.getSchemaName();
            row.table = ch.getTableName();
            row.pk = pk;
            if ("D".equals(ch.getOp())) {
                row.op = "D";
                row.values = new LinkedHashMap<>();
            } else {
                row.op = prior != null && "I".equals(prior.op) ? "I" : ch.getOp();
                row.values = new LinkedHashMap<>();
                if (prior != null && !"D".equals(prior.op) && prior.values != null) {
                    row.values.putAll(prior.values);
                }
                row.values.putAll(values);
                // Some CDC engines return only changed columns for UPDATE. The key still
                // belongs in the UPSERT/MERGE column set even when it is absent from values.
                pk.forEach(row.values::putIfAbsent);
            }
            tableRows.put(pkKey, row);
        }

        int upserts = 0, deletes = 0;
        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            for (Map.Entry<String, Map<String, NetRow>> tbl : net.entrySet()) {
                for (NetRow row : tbl.getValue().values()) {
                    if ("D".equals(row.op)) { deletes += applyDelete(c, row); }
                    else { upserts += applyUpsert(c, dialect, row); }
                }
            }
            c.commit();
        } catch (ApiException e) {
            rollbackQuietly(c);
            throw e;
        } catch (Exception e) {
            rollbackQuietly(c);
            throw ApiException.bad("CDC incremental apply failed: " + rootMessage(e));
        } finally {
            try { c.setAutoCommit(originalAutoCommit); } catch (Exception ignored) { }
        }
        return new ApplyResult(upserts, deletes, skippedNoPk, net.size(), consumedThroughId);
    }

    // ------------------------------------------------------------------ SQL

    private int applyDelete(Connection c, NetRow row) throws Exception {
        List<String> cols = new ArrayList<>(row.pk.keySet());
        String where = String.join(" AND ", cols.stream().map(x -> q(x) + " = ?").toList());
        String sql = "DELETE FROM " + q(row.schema) + "." + q(row.table) + " WHERE " + where;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String col : cols) ps.setString(i++, row.pk.get(col));
            return ps.executeUpdate() > 0 ? 1 : 0;
        }
    }

    private int applyUpsert(Connection c, SqlDialect dialect, NetRow row) throws Exception {
        List<String> cols = new ArrayList<>(row.values.keySet());
        if (cols.isEmpty()) cols = new ArrayList<>(row.pk.keySet());
        List<String> pkCols = new ArrayList<>(row.pk.keySet());
        String colList = String.join(", ", cols.stream().map(CdcIncrementalApplier::q).toList());
        String params = String.join(", ", cols.stream().map(x -> "?").toList());
        String conflict = String.join(", ", pkCols.stream().map(CdcIncrementalApplier::q).toList());

        List<String> updates = new ArrayList<>();
        for (String col : cols) if (!pkCols.contains(col)) updates.add(q(col) + " = EXCLUDED." + q(col));
        String sql;
        if (dialect == SqlDialect.H2) {
            sql = "MERGE INTO " + q(row.schema) + "." + q(row.table) + " (" + colList + ") KEY ("
                    + conflict + ") VALUES (" + params + ")";
        } else {
            String onConflict = updates.isEmpty()
                    ? " ON CONFLICT (" + conflict + ") DO NOTHING"
                    : " ON CONFLICT (" + conflict + ") DO UPDATE SET " + String.join(", ", updates);
            sql = "INSERT INTO " + q(row.schema) + "." + q(row.table) + " (" + colList + ") VALUES ("
                    + params + ")" + onConflict;
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String col : cols) ps.setString(i++, row.values.get(col));
            ps.executeUpdate();
            return 1;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Connection openTarget(DataSourceEntity ds) throws Exception {
        Properties p = new Properties();
        if (ds.getUsername() != null) p.setProperty("user", ds.getUsername());
        if (ds.getPassword() != null) p.setProperty("password", ds.getPassword());
        // Bind string params as untyped so the server casts them to each column's real type.
        p.setProperty("stringtype", "unspecified");
        return DriverManager.getConnection(ds.getJdbcUrl(), p);
    }

    private static boolean isPostgres(DataSourceEntity ds) {
        String url = ds.getJdbcUrl() == null ? "" : ds.getJdbcUrl().toLowerCase(Locale.ROOT);
        String kind = ds.getKind() == null ? "" : ds.getKind().toLowerCase(Locale.ROOT);
        return url.startsWith("jdbc:postgresql:") || kind.contains("postgres");
    }

    private static String tableKey(String schema, String table) {
        return (schema == null ? "public" : schema) + "." + table;
    }

    private Map<String, String> readMap(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return new LinkedHashMap<>();
        try { return json.readValue(jsonStr, new TypeReference<LinkedHashMap<String, String>>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { return String.valueOf(value); }
    }

    /** Double-quote an identifier, escaping embedded quotes. */
    private static String q(String ident) {
        return "\"" + (ident == null ? "" : ident.replace("\"", "\"\"")) + "\"";
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.toString() : r.getMessage();
    }

    private static void rollbackQuietly(Connection c) {
        try { c.rollback(); } catch (Exception ignored) { }
    }
}
