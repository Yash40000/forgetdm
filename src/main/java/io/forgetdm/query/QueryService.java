package io.forgetdm.query;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ad-hoc read-only query runner for the Data Explorer page.
 * Safety: only a single SELECT / WITH…SELECT statement is allowed, the connection is set read-only
 * and the transaction is rolled back (never committed), the result is capped at 1000 rows, and the
 * query has a timeout. This is for previewing data, not for changing it.
 */
@Service
public class QueryService {

    public static final int MAX_ROWS = 1000;
    private static final int CELL_CAP = 1000;
    private static final int QUERY_TIMEOUT_SEC = 30;

    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;

    public QueryService(DataSourceService dataSources, ConnectionFactory connections, AuditService audit) {
        this.dataSources = dataSources; this.connections = connections; this.audit = audit;
    }

    public Map<String, Object> run(Long dataSourceId, String sql) {
        if (dataSourceId == null) throw ApiException.bad("dataSourceId is required");
        if (sql == null || sql.isBlank()) throw ApiException.bad("A SELECT query is required");

        String stmt = sql.trim();
        while (stmt.endsWith(";")) stmt = stmt.substring(0, stmt.length() - 1).trim();
        if (stmt.contains(";")) throw ApiException.bad("Only a single statement is allowed (remove the ';').");
        String lower = stmt.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with")))
            throw ApiException.bad("Only read-only SELECT (or WITH … SELECT) queries are allowed here.");

        DataSourceEntity ds = dataSources.get(dataSourceId);
        long start = System.currentTimeMillis();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;

        try (Connection c = connections.open(ds)) {
            try { c.setReadOnly(true); } catch (Exception ignore) { /* best effort */ }
            try { c.setAutoCommit(false); } catch (Exception ignore) { }
            try (Statement st = c.createStatement()) {
                st.setMaxRows(MAX_ROWS + 1);          // +1 so we can detect truncation
                try { st.setQueryTimeout(QUERY_TIMEOUT_SEC); } catch (Exception ignore) { }
                try (ResultSet rs = st.executeQuery(stmt)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    for (int i = 1; i <= n; i++) columns.add(md.getColumnLabel(i));
                    int count = 0;
                    while (rs.next()) {
                        if (count >= MAX_ROWS) { truncated = true; break; }
                        List<Object> row = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) row.add(cell(rs, i));
                        rows.add(row);
                        count++;
                    }
                }
            }
            try { c.rollback(); } catch (Exception ignore) { }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Query failed: " + e.getMessage());
        }

        audit.log("system", "QUERY_RUN", "ds=" + dataSourceId + " rows=" + rows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("rowCount", rows.size());
        out.put("truncated", truncated);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    private static Object cell(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        if (v == null) return null;
        if (v instanceof Number || v instanceof Boolean) return v;
        String s = String.valueOf(v);
        return s.length() > CELL_CAP ? s.substring(0, CELL_CAP) + "…" : s;
    }
}
