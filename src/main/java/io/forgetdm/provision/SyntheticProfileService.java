package io.forgetdm.provision;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * "Learn from production": profile a real table and suggest realistic generators + parameters so generated
 * synthetic data statistically resembles the source — value frequencies (→ WEIGHTED), numeric mean/stddev
 * (→ NORMAL_*), ranges (→ *_RANGE / DATE_BETWEEN), null rates, distinct counts and name heuristics. The
 * suggestions map 1:1 onto the Synthetic plan's columns, so the UI can pre-fill a table from a real one.
 */
@Service
public class SyntheticProfileService {

    private static final int SAMPLE = 20_000;       // rows scanned per column (bounded, best-effort)
    private static final int LOW_CARDINALITY = 25;  // ≤ this many distinct values → WEIGHTED set
    private static final int TOP_VALUES = 15;

    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;

    public SyntheticProfileService(DataSourceService dataSources, ConnectionFactory connections, AuditService audit) {
        this.dataSources = dataSources;
        this.connections = connections;
        this.audit = audit;
    }

    public Map<String, Object> profile(Long dataSourceId, String schemaName, String table) {
        return profile(dataSourceId, schemaName, table, true);
    }

    public Map<String, Object> profile(Long dataSourceId, String schemaName, String table, boolean bankingSafeProfile) {
        if (dataSourceId == null) throw ApiException.bad("dataSourceId is required");
        if (table == null || table.isBlank()) throw ApiException.bad("table is required");
        DataSourceEntity ds = dataSources.get(dataSourceId);
        List<Map<String, Object>> columns = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long total = 0;
        boolean randomSampled = false;
        try (Connection c = connections.openForBulk(ds)) {   // profiling scans/aggregates on big tables can exceed the interactive read timeout
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            String fq = qualified(schema, table);
            SqlDialect dialect = SqlDialect.fromConnection(c);
            total = scalarLong(c, "SELECT COUNT(*) FROM " + fq, 0);
            randomSampled = total > SAMPLE && dialect != SqlDialect.GENERIC;

            Set<String> pks = primaryKeys(c, schema, table);
            try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    int jdbc = rs.getInt("DATA_TYPE");
                    String typeName = rs.getString("TYPE_NAME");
                    boolean pk = pks.contains(name.toLowerCase(Locale.ROOT));
                    try {
                        Map<String, Object> profiled = profileColumn(c, dialect, fq, total, name, jdbc, typeName, pk, bankingSafeProfile);
                        Object columnWarnings = profiled.get("warnings");
                        if (columnWarnings instanceof Collection<?> list) {
                            for (Object warning : list) if (warning != null) warnings.add(name + ": " + warning);
                        }
                        columns.add(profiled);
                    } catch (Exception e) {
                        columns.add(fallbackColumn(name, jdbc, typeName, pk, e.getMessage()));
                    }
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Profiling failed: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", table);
        if (schemaName != null) out.put("schema", schemaName);
        out.put("rowCount", total);
        out.put("sampled", Math.min(total, SAMPLE));
        out.put("sampling", randomSampled ? "random" : "all");   // random sample vs full/small-table scan
        out.put("bankingSafeProfile", bankingSafeProfile);
        if (!warnings.isEmpty()) out.put("warnings", warnings);
        out.put("columns", columns);
        audit.log("system", "SYNTHETIC_PROFILED",
                "datasource=" + dataSourceId + " schema=" + schemaName + " table=" + table
                        + " bankingSafe=" + bankingSafeProfile + " sampled=" + Math.min(total, SAMPLE));
        return out;
    }

    private Map<String, Object> profileColumn(Connection c, SqlDialect dialect, String fq, long total,
                                              String col, int jdbc, String typeName,
                                              boolean pk, boolean bankingSafeProfile) throws SQLException {
        String sample = sampleSource(dialect, fq, qq(col), total);
        String countSql = "SELECT COUNT(*) n, COUNT(v) nn, COUNT(DISTINCT v) d FROM ";
        long n = 1, nn = 0, distinct = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(countSql + sample)) {
            if (rs.next()) { n = Math.max(1, rs.getLong(1)); nn = rs.getLong(2); distinct = rs.getLong(3); }
        } catch (SQLException sampleErr) {
            // native sampling not applicable to this object (e.g. a view) → retry with a deterministic capped scan
            sample = cappedSource(dialect, fq, qq(col));
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(countSql + sample)) {
                if (rs.next()) { n = Math.max(1, rs.getLong(1)); nn = rs.getLong(2); distinct = rs.getLong(3); }
            }
        }
        double nullRate = (double) (n - nn) / n;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", col);
        out.put("sqlType", sqlType(jdbc));
        out.put("nullRate", Math.round(nullRate * 1000) / 1000.0);
        out.put("distinct", distinct);
        out.put("primaryKey", pk);

        String gen = "ALPHANUMERIC", p1 = "", p2 = "", note = "";
        boolean allDistinct = nn > 0 && distinct >= nn;
        SyntheticDataSafety.Classification safety = SyntheticDataSafety.classify(col, jdbc, typeName);
        boolean suppressSourceDistribution = bankingSafeProfile && !safety.sourceDistributionAllowed();
        List<String> warnings = new ArrayList<>();
        if (suppressSourceDistribution) warnings.add(safety.warning());

        if (pk || allDistinct) {
            if (isNumeric(jdbc)) { gen = "SEQUENCE"; note = "unique key"; }
            else { gen = "PADDED_SEQUENCE"; p1 = "12"; note = "unique key"; }
        } else if (suppressSourceDistribution && SyntheticDataSafety.directSafeGenerator(safety.generator())) {
            gen = safety.generator();
            note = "banking safe generator by column classification";
        } else if (isNumeric(jdbc)) {
            double[] s = numericStats(c, sample);   // min, max, avg, stddev
            if (!suppressSourceDistribution && distinct > 0 && distinct <= LOW_CARDINALITY) {
                gen = "WEIGHTED"; p1 = topValues(c, sample); note = distinct + " distinct values";
            } else if (isInteger(jdbc)) {
                if (s[3] > 0) { gen = "NORMAL_INT"; p1 = String.valueOf(Math.round(s[2])); p2 = String.valueOf(Math.round(s[3])); note = "mean " + Math.round(s[2]) + " ± " + Math.round(s[3]); }
                else { gen = "INT_RANGE"; p1 = String.valueOf((long) s[0]); p2 = String.valueOf((long) s[1]); note = "range"; }
            } else {
                if (s[3] > 0) { gen = "NORMAL_DECIMAL"; p1 = round2(s[2]); p2 = round2(s[3]); note = "mean " + round2(s[2]) + " ± " + round2(s[3]); }
                else { gen = "DECIMAL_RANGE"; p1 = round2(s[0]); p2 = round2(s[1]); note = "range"; }
            }
        } else if (isDate(jdbc)) {
            if (suppressSourceDistribution && SyntheticDataSafety.directSafeGenerator(safety.generator())) {
                gen = safety.generator();
                note = "banking safe generator by column classification";
            } else {
                String[] mm = minMaxStr(c, sample);
                gen = "DATE_BETWEEN"; p1 = mm[0]; p2 = mm[1]; note = "observed date range";
            }
        } else if (isBoolean(jdbc)) {
            gen = "BOOLEAN_WEIGHTED"; p1 = String.valueOf(pctTrue(c, sample)); note = p1 + "% true";
        } else {
            if (!suppressSourceDistribution && distinct > 0 && distinct <= LOW_CARDINALITY) {
                gen = "WEIGHTED"; p1 = topValues(c, sample); note = distinct + " distinct values";
            } else {
                String h = SyntheticDataSafety.suggestedGenerator(col);
                gen = h; note = "by column name";
                if (suppressSourceDistribution) note = "banking safe generator by column classification";
            }
        }

        out.put("generator", gen);
        out.put("param1", p1);
        out.put("param2", p2);
        if (safety.sensitive()) out.put("sensitivity", safety.category());
        if (suppressSourceDistribution) out.put("sourceDistribution", "suppressed");
        else if ("WEIGHTED".equals(gen)) out.put("sourceDistribution", "used");
        if (!warnings.isEmpty()) out.put("warnings", warnings);
        if (!note.isBlank()) out.put("note", note);
        return out;
    }

    private Map<String, Object> fallbackColumn(String col, int jdbc, String typeName, boolean pk, String err) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", col);
        out.put("sqlType", sqlType(jdbc));
        out.put("primaryKey", pk);
        out.put("generator", pk ? "SEQUENCE" : isNumeric(jdbc) ? "INT_RANGE" : isDate(jdbc) ? "DATE_RECENT" : "ALPHANUMERIC");
        out.put("param1", "");
        out.put("param2", "");
        out.put("note", "profile skipped: " + (err == null ? "n/a" : err));
        return out;
    }

    private double[] numericStats(Connection c, String sample) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MIN(v), MAX(v), AVG(v), STDDEV(v) FROM " + sample)) {
            if (rs.next()) return new double[]{ rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4) };
        } catch (Exception ignore) { }
        return new double[]{0, 0, 0, 0};
    }

    private String[] minMaxStr(Connection c, String sample) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MIN(v), MAX(v) FROM " + sample)) {
            if (rs.next()) {
                String lo = trimDate(rs.getString(1)), hi = trimDate(rs.getString(2));
                return new String[]{ lo == null ? "" : lo, hi == null ? "" : hi };
            }
        } catch (Exception ignore) { }
        return new String[]{"", ""};
    }

    private int pctTrue(Connection c, String sample) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT AVG(CASE WHEN v IN ('t','true','1','TRUE','T','Y') THEN 1.0 ELSE 0.0 END) FROM " + sample)) {
            if (rs.next()) return (int) Math.round(rs.getDouble(1) * 100);
        } catch (Exception ignore) { }
        return 50;
    }

    private String topValues(Connection c, String sample) {
        StringBuilder sb = new StringBuilder();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT v, COUNT(*) k FROM " + sample + " WHERE v IS NOT NULL GROUP BY v ORDER BY k DESC LIMIT " + TOP_VALUES)) {
            while (rs.next()) {
                String v = rs.getString(1);
                long k = rs.getLong(2);
                if (v == null) continue;
                v = v.replace('|', ' ').replace(':', ' ').trim();
                if (v.isEmpty()) continue;
                if (sb.length() > 0) sb.append('|');
                sb.append(v).append(':').append(k);
            }
        } catch (Exception ignore) { }
        return sb.length() == 0 ? "A:1" : sb.toString();
    }

    private Set<String> primaryKeys(Connection c, String schema, String table) {
        Set<String> pks = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, schema, table)) {
            while (rs.next()) pks.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        } catch (Exception ignore) { }
        return pks;
    }

    private static String nameHeuristic(String col) {
        String n = col.toLowerCase(Locale.ROOT);
        String words = n.replaceAll("[^a-z0-9]+", " ");
        boolean femaleHint = words.matches(".*\\b(female|mother|wife|daughter|girl)\\b.*");
        boolean maleHint = words.matches(".*\\b(male|father|husband|son|boy)\\b.*");
        if (n.contains("email") || n.contains("mail")) return "EMAIL";
        if (n.contains("phone") || n.contains("mobile") || n.contains("tel")) return "PHONE_US";
        if (femaleHint && (n.contains("first") || n.contains("name"))) return "FEMALE_FIRST_NAME";
        if (maleHint && (n.contains("first") || n.contains("name"))) return "MALE_FIRST_NAME";
        if (n.contains("first")) return "FIRST_NAME";
        if (n.contains("last") || n.contains("surname")) return "LAST_NAME";
        if (n.contains("name")) return "FULL_NAME";
        if (n.contains("city")) return "CITY";
        if (n.contains("state") || n.contains("province")) return "STATE";
        if (n.contains("zip") || n.contains("postal")) return "ZIP";
        if (n.contains("street") || n.contains("address") || n.contains("addr")) return "STREET_ADDRESS";
        if (n.contains("country")) return "COUNTRY_CODE";
        if (n.contains("ssn")) return "SSN";
        if (n.contains("iban")) return "IBAN_LIKE";
        if (n.contains("bic") || n.contains("swift")) return "BIC";
        if (n.contains("account") || n.contains("acct")) return "ACCOUNT_NUMBER";
        if (n.contains("routing")) return "ROUTING_NUMBER_US";
        if (n.contains("card")) return "CREDIT_CARD_VISA";
        if (n.contains("company") || n.contains("org") || n.contains("employer")) return "COMPANY";
        if (n.contains("status")) return "STATUS";
        if (n.contains("uuid") || n.contains("guid")) return "UUID";
        if (n.contains("ip")) return "IPV4";
        if (n.contains("url") || n.contains("link")) return "URL";
        return "ALPHANUMERIC";
    }

    // ---- type helpers ----

    private static boolean isNumeric(int t) {
        return t == Types.TINYINT || t == Types.SMALLINT || t == Types.INTEGER || t == Types.BIGINT
                || t == Types.FLOAT || t == Types.REAL || t == Types.DOUBLE || t == Types.NUMERIC || t == Types.DECIMAL;
    }
    private static boolean isInteger(int t) {
        return t == Types.TINYINT || t == Types.SMALLINT || t == Types.INTEGER || t == Types.BIGINT;
    }
    private static boolean isDate(int t) { return t == Types.DATE || t == Types.TIMESTAMP || t == Types.TIMESTAMP_WITH_TIMEZONE; }
    private static boolean isBoolean(int t) { return t == Types.BOOLEAN || t == Types.BIT; }

    private static String sqlType(int t) {
        if (isInteger(t)) return "INTEGER";
        if (t == Types.NUMERIC || t == Types.DECIMAL || t == Types.FLOAT || t == Types.REAL || t == Types.DOUBLE) return "DECIMAL";
        if (t == Types.DATE) return "DATE";
        if (t == Types.TIMESTAMP || t == Types.TIMESTAMP_WITH_TIMEZONE) return "TIMESTAMP";
        if (isBoolean(t)) return "BOOLEAN";
        return "VARCHAR";
    }

    private static String trimDate(String v) {
        if (v == null) return null;
        return v.length() >= 10 ? v.substring(0, 10) : v;
    }
    private static String round2(double v) { return String.format(Locale.US, "%.2f", v); }

    private long scalarLong(Connection c, String sql, long dflt) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : dflt;
        } catch (Exception e) { return dflt; }
    }

    /**
     * Build a sampled FROM-source {@code (SELECT <colExpr> AS v FROM <table> <sampling>) s} that draws a
     * REPRESENTATIVE random sample rather than the first physical rows (which are usually clustered by primary
     * key / insertion order and badly skew distribution estimates). Small tables (≤ SAMPLE) are scanned in
     * full. Uses each engine's native sampling (Postgres/DB2 BERNOULLI, Oracle SAMPLE, SQL Server TABLESAMPLE,
     * MySQL RAND() predicate, H2 ORDER BY RAND()); unknown engines fall back to a plain row cap.
     */
    private static String sampleSource(SqlDialect d, String fq, String colExpr, long total) {
        if (total <= SAMPLE || d == null)                       // small table: use everything (no bias to remove)
            return "(SELECT " + colExpr + " AS v FROM " + fq + ") s";
        double pct = Math.min(100.0, ((double) SAMPLE / total) * 100.0 * 3.0);   // 3× oversample, then cap rows
        double frac = Math.min(1.0, pct / 100.0);
        return switch (d) {
            case POSTGRES -> "(SELECT " + colExpr + " AS v FROM " + fq + " TABLESAMPLE BERNOULLI (" + pctStr(pct) + ") LIMIT " + SAMPLE + ") s";
            case DB2      -> "(SELECT " + colExpr + " AS v FROM " + fq + " TABLESAMPLE BERNOULLI (" + pctStr(pct) + ") FETCH FIRST " + SAMPLE + " ROWS ONLY) s";
            case ORACLE   -> "(SELECT " + colExpr + " AS v FROM " + fq + " SAMPLE (" + pctStr(pct) + ") WHERE ROWNUM <= " + SAMPLE + ") s";
            case SQLSERVER-> "(SELECT TOP (" + SAMPLE + ") " + colExpr + " AS v FROM " + fq + " TABLESAMPLE (" + pctStr(pct) + " PERCENT)) s";
            case MYSQL    -> "(SELECT " + colExpr + " AS v FROM " + fq + " WHERE RAND() < " + pctStr(frac) + " LIMIT " + SAMPLE + ") s";
            case H2       -> "(SELECT " + colExpr + " AS v FROM " + fq + " ORDER BY RAND() LIMIT " + SAMPLE + ") s";
            case TERADATA -> "(SELECT " + colExpr + " AS v FROM " + fq + " SAMPLE " + SAMPLE + ") s";
            default       -> cappedSource(d, fq, colExpr);
        };
    }

    /** Deterministic first-N capped source (dialect-correct row limit). Used for GENERIC and as a fallback when
     *  native sampling isn't applicable to the object (e.g. a view doesn't support TABLESAMPLE). */
    private static String cappedSource(SqlDialect d, String fq, String colExpr) {
        return switch (d == null ? SqlDialect.GENERIC : d) {
            case ORACLE    -> "(SELECT " + colExpr + " AS v FROM " + fq + " WHERE ROWNUM <= " + SAMPLE + ") s";
            case SQLSERVER -> "(SELECT TOP (" + SAMPLE + ") " + colExpr + " AS v FROM " + fq + ") s";
            case DB2       -> "(SELECT " + colExpr + " AS v FROM " + fq + " FETCH FIRST " + SAMPLE + " ROWS ONLY) s";
            case TERADATA  -> "(SELECT " + colExpr + " AS v FROM " + fq + " SAMPLE " + SAMPLE + ") s";
            default        -> "(SELECT " + colExpr + " AS v FROM " + fq + " LIMIT " + SAMPLE + ") s";
        };
    }

    /** Plain decimal (no scientific notation) for SQL sampling clauses. */
    private static String pctStr(double v) {
        return java.math.BigDecimal.valueOf(v)
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String qq(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    private static String qualified(String schema, String table) {
        return (schema == null || schema.isBlank()) ? qq(table) : qq(schema) + "." + qq(table);
    }
}
