package io.forgetdm.cdc;

import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * True CDC for Oracle via LogMiner.
 *
 * Oracle has no replication slot; the durable checkpoint is the SCN (System Change Number),
 * carried through the same {@link CdcProvider} contract as Postgres' LSN. Each poll registers
 * the redo/archived log files that cover (lastScn, currentScn], runs {@code DBMS_LOGMNR}, reads
 * committed changes from {@code V$LOGMNR_CONTENTS}, parses each {@code SQL_REDO} into a row change,
 * then advances the checkpoint to the current SCN. Only changed rows are read — no table scan.
 *
 * Prerequisites (reported by preflight): the database in ARCHIVELOG mode, supplemental logging
 * enabled (minimal + primary-key; ALL columns for full update images), and the connecting user
 * granted LogMiner access (e.g. LOGMINING + SELECT ANY DICTIONARY + EXECUTE on DBMS_LOGMNR, or
 * an equivalent role).
 */
@Component
public class OracleCdcProvider implements CdcProvider {

    @Override
    public boolean supports(DataSourceEntity ds) {
        String url = ds.getJdbcUrl() == null ? "" : ds.getJdbcUrl().toLowerCase(Locale.ROOT);
        String kind = ds.getKind() == null ? "" : ds.getKind().toLowerCase(Locale.ROOT);
        return url.startsWith("jdbc:oracle:") || kind.contains("oracle");
    }

    @Override
    public String mechanism() { return "Oracle LogMiner (redo log)"; }

    @Override
    public String pluginName() { return "logminer"; }

    // ------------------------------------------------------------------ preflight

    @Override
    public Preflight preflight(DataSourceEntity ds) {
        List<String> messages = new ArrayList<>();
        String logMode = "unknown";
        boolean privileged = false;
        boolean supplementalMin = false;
        try (Connection c = open(ds)) {
            try {
                logMode = scalar(c, "SELECT LOG_MODE FROM V$DATABASE");
                String suppMin = scalar(c, "SELECT SUPPLEMENTAL_LOG_DATA_MIN FROM V$DATABASE");
                String suppPk = scalar(c, "SELECT SUPPLEMENTAL_LOG_DATA_PK FROM V$DATABASE");
                privileged = true; // reading V$DATABASE at all means we have dictionary access
                supplementalMin = suppMin != null && !"NO".equalsIgnoreCase(suppMin);

                if (!"ARCHIVELOG".equalsIgnoreCase(logMode)) {
                    // Not a hard blocker: LogMiner can still mine the current ONLINE redo log. ARCHIVELOG
                    // is required only to reach changes that have already rotated out of the online logs.
                    messages.add("Recommended: database is in " + logMode + " mode, so only changes still in "
                            + "the current online redo log can be captured. For durable/historic capture enable "
                            + "ARCHIVELOG (as SYSDBA: 'SHUTDOWN IMMEDIATE; STARTUP MOUNT; ALTER DATABASE ARCHIVELOG; "
                            + "ALTER DATABASE OPEN;').");
                }
                if (!supplementalMin) {
                    messages.add("Minimal supplemental logging is off. Run "
                            + "'ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;'.");
                }
                if (suppPk == null || "NO".equalsIgnoreCase(suppPk)) {
                    messages.add("Primary-key supplemental logging is off. Run "
                            + "'ALTER DATABASE ADD SUPPLEMENTAL LOG DATA (PRIMARY KEY) COLUMNS;' "
                            + "(or (ALL) COLUMNS for full update images).");
                }
            } catch (Exception dictErr) {
                messages.add("Cannot read V$DATABASE / redo dictionary. Grant LogMiner access to '"
                        + ds.getUsername() + "': 'GRANT LOGMINING, SELECT ANY DICTIONARY TO "
                        + safeIdent(ds.getUsername()) + ";' and 'GRANT EXECUTE ON DBMS_LOGMNR TO "
                        + safeIdent(ds.getUsername()) + ";'.");
            }
        } catch (Exception e) {
            throw ApiException.bad("CDC preflight failed: " + rootMessage(e));
        }
        // Capture is possible as long as we have dictionary access and minimal supplemental logging;
        // ARCHIVELOG only widens the reachable history (see recommendation above).
        boolean ok = supplementalMin && privileged;
        return new Preflight(ok, logMode, privileged, messages);
    }

    @Override
    public String currentLogPosition(DataSourceEntity ds) {
        try (Connection c = open(ds)) {
            return scalar(c, "SELECT CURRENT_SCN FROM V$DATABASE");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Long lag(DataSourceEntity ds, String confirmedPosition) {
        if (confirmedPosition == null || !confirmedPosition.matches("\\d+")) return null;
        try (Connection c = open(ds)) {
            String cur = scalar(c, "SELECT CURRENT_SCN FROM V$DATABASE");
            if (cur == null || !cur.matches("\\d+")) return null;
            return new java.math.BigInteger(cur).subtract(new java.math.BigInteger(confirmedPosition)).longValue();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String lagUnit() { return "SCN"; }

    // ------------------------------------------------------------------ checkpoint (no slot)

    @Override
    public SlotInfo createSlot(DataSourceEntity ds, String slotName) {
        // Oracle keeps no server-side slot; the checkpoint is simply the current SCN.
        try (Connection c = open(ds)) {
            String scn = scalar(c, "SELECT CURRENT_SCN FROM V$DATABASE");
            return new SlotInfo(slotName, scn, scn);
        } catch (Exception e) {
            throw ApiException.bad("Cannot read current SCN (needs dictionary access): " + rootMessage(e));
        }
    }

    @Override
    public void dropSlot(DataSourceEntity ds, String slotName) {
        // Nothing persistent to drop for Oracle LogMiner.
    }

    // ------------------------------------------------------------------ poll

    @Override
    public PollResult poll(DataSourceEntity ds, CdcCaptureEntity capture, int maxChanges, long budgetMillis) {
        String schema = capture.getSchemaName();
        String schemaUpper = schema == null || schema.isBlank() ? null : schema.trim().toUpperCase(Locale.ROOT);
        BigDecimal startScn = new BigDecimal(safeScn(capture.getConfirmedLsn(), "0"));
        List<DecodedChange> out = new ArrayList<>();
        Map<String, List<String>> pkCache = new LinkedHashMap<>();
        BigDecimal confirmedScn = startScn;
        boolean reachedEnd = true;

        try (Connection c = open(ds)) {
            BigDecimal endScn = new BigDecimal(scalar(c, "SELECT CURRENT_SCN FROM V$DATABASE"));
            if (endScn.compareTo(startScn) <= 0) {
                return new PollResult(out, endScn.toPlainString(), true);
            }

            Set<String> logs = logFilesCovering(c, startScn, endScn);
            if (logs.isEmpty()) throw ApiException.bad(
                    "No redo/archived log files found covering SCN range " + startScn + ".." + endScn
                            + " — ensure ARCHIVELOG is on and logs are retained.");

            boolean first = true;
            for (String file : logs) { addLogFile(c, file, first); first = false; }

            startLogMiner(c, startScn, endScn);
            try {
                String sql = "SELECT SCN, SEG_OWNER, TABLE_NAME, OPERATION, SQL_REDO "
                        + "FROM V$LOGMNR_CONTENTS WHERE OPERATION IN ('INSERT','UPDATE','DELETE') "
                        + (schemaUpper == null ? "" : "AND SEG_OWNER = ? ")
                        + "AND SCN > ? AND SCN <= ? ORDER BY SCN, RS_ID, SSN";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int idx = 1;
                    if (schemaUpper != null) ps.setString(idx++, schemaUpper);
                    ps.setBigDecimal(idx++, startScn);
                    ps.setBigDecimal(idx, endScn);
                    try (ResultSet rs = ps.executeQuery()) {
                        BigDecimal softLimitScn = null;
                        while (rs.next()) {
                            BigDecimal rowScn = rs.getBigDecimal("SCN");
                            // Never split one SCN across polls: SCN is the resume key and the next
                            // query uses SCN > checkpoint. Finish the current commit even when it
                            // takes us slightly beyond maxChanges, then stop before the next SCN.
                            if (softLimitScn != null && rowScn.compareTo(softLimitScn) > 0) {
                                reachedEnd = false;
                                break;
                            }
                            DecodedChange ch = parseRedo(
                                    rs.getString("OPERATION"),
                                    rs.getString("SEG_OWNER"),
                                    rs.getString("TABLE_NAME"),
                                    rs.getString("SQL_REDO"));
                            if (ch == null) continue;
                            ch.lsn = rowScn.toPlainString();
                            attachPk(ch, pkCache, ds);
                            out.add(ch);
                            confirmedScn = rowScn;
                            if (out.size() >= maxChanges && softLimitScn == null) softLimitScn = rowScn;
                        }
                    }
                }
            } finally {
                endLogMiner(c);
            }
            if (reachedEnd) confirmedScn = endScn;
            return new PollResult(out, confirmedScn.toPlainString(), reachedEnd);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Oracle CDC poll failed: " + rootMessage(e));
        }
    }

    // ------------------------------------------------------------------ LogMiner control

    private Set<String> logFilesCovering(Connection c, BigDecimal startScn, BigDecimal endScn) throws Exception {
        Set<String> files = new LinkedHashSet<>();
        // Archived logs overlapping the range.
        String arch = "SELECT NAME FROM V$ARCHIVED_LOG WHERE NAME IS NOT NULL AND DELETED='NO' "
                + "AND STANDBY_DEST='NO' AND NEXT_CHANGE# > ? AND FIRST_CHANGE# <= ? ORDER BY FIRST_CHANGE#";
        try (PreparedStatement ps = c.prepareStatement(arch)) {
            ps.setBigDecimal(1, startScn);
            ps.setBigDecimal(2, endScn);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) files.add(rs.getString(1)); }
        } catch (Exception ignored) { /* archivelog may be off */ }
        // Online logs (current and any whose range overlaps).
        String online = "SELECT f.MEMBER FROM V$LOG l JOIN V$LOGFILE f ON f.GROUP# = l.GROUP# "
                + "WHERE l.STATUS = 'CURRENT' OR l.NEXT_CHANGE# > ?";
        try (PreparedStatement ps = c.prepareStatement(online)) {
            ps.setBigDecimal(1, startScn);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) files.add(rs.getString(1)); }
        } catch (Exception ignored) { /* fall through */ }
        return files;
    }

    private void addLogFile(Connection c, String file, boolean first) throws Exception {
        String opt = first ? "DBMS_LOGMNR.NEW" : "DBMS_LOGMNR.ADDFILE";
        try (CallableStatement cs = c.prepareCall(
                "BEGIN DBMS_LOGMNR.ADD_LOGFILE(LOGFILENAME => ?, OPTIONS => " + opt + "); END;")) {
            cs.setString(1, file);
            cs.execute();
        }
    }

    private void startLogMiner(Connection c, BigDecimal startScn, BigDecimal endScn) throws Exception {
        try (CallableStatement cs = c.prepareCall(
                "BEGIN DBMS_LOGMNR.START_LOGMNR(STARTSCN => ?, ENDSCN => ?, OPTIONS => "
                        + "DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG + DBMS_LOGMNR.COMMITTED_DATA_ONLY "
                        + "+ DBMS_LOGMNR.NO_SQL_DELIMITER + DBMS_LOGMNR.NO_ROWID_IN_STMT); END;")) {
            cs.setBigDecimal(1, startScn);
            cs.setBigDecimal(2, endScn);
            cs.execute();
        }
    }

    private void endLogMiner(Connection c) {
        try (CallableStatement cs = c.prepareCall("BEGIN DBMS_LOGMNR.END_LOGMNR; END;")) {
            cs.execute();
        } catch (Exception ignored) { /* best effort */ }
    }

    // ------------------------------------------------------------------ SQL_REDO parsing

    /**
     * Parse a LogMiner {@code SQL_REDO} statement into a change. OPERATION drives the shape:
     *   INSERT: insert into "S"."T"("C1","C2") values (v1, v2)
     *   UPDATE: update "S"."T" set "C1" = v1 where "C2" = v2 and ...
     *   DELETE: delete from "S"."T" where "C1" = v1 and ...
     */
    static DecodedChange parseRedo(String operation, String owner, String table, String redo) {
        if (redo == null || operation == null) return null;
        DecodedChange ch = new DecodedChange();
        ch.schema = owner;
        ch.table = table;
        ch.op = switch (operation.toUpperCase(Locale.ROOT)) {
            case "INSERT" -> "I";
            case "UPDATE" -> "U";
            case "DELETE" -> "D";
            default -> null;
        };
        if (ch.op == null) return null;

        String lower = redo.toLowerCase(Locale.ROOT);
        try {
            if ("I".equals(ch.op)) {
                int lp = redo.indexOf('(');
                int rp = matchParen(redo, lp);
                int vpos = lower.indexOf("values", rp);
                int vlp = redo.indexOf('(', vpos);
                int vrp = matchParen(redo, vlp);
                List<String> cols = splitTopLevel(redo.substring(lp + 1, rp), ",");
                List<String> vals = splitTopLevel(redo.substring(vlp + 1, vrp), ",");
                for (int i = 0; i < cols.size() && i < vals.size(); i++) {
                    ch.values.put(unquoteIdent(cols.get(i)), literal(vals.get(i)));
                }
            } else if ("D".equals(ch.op)) {
                int w = indexOfKeyword(lower, " where ", 0);
                if (w >= 0) ch.values.putAll(parsePairs(redo.substring(w + 7), " and "));
            } else { // UPDATE
                int setPos = indexOfKeyword(lower, " set ", 0);
                int wherePos = indexOfKeyword(lower, " where ", setPos < 0 ? 0 : setPos);
                String setPart = redo.substring(setPos + 5, wherePos < 0 ? redo.length() : wherePos);
                ch.values.putAll(parsePairs(setPart, ","));
                if (wherePos >= 0) {
                    // WHERE carries the old row image incl. the primary key.
                    Map<String, String> where = parsePairs(redo.substring(wherePos + 7), " and ");
                    where.forEach(ch.values::putIfAbsent);
                }
            }
        } catch (Exception e) {
            // Parsing best-effort: keep whatever we extracted rather than dropping the change.
        }
        return ch;
    }

    /** Split {@code "COL" = value} assignments separated by {@code sep} (top level), into a map. */
    static Map<String, String> parsePairs(String s, String sep) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String piece : splitTopLevel(s, sep)) {
            int eq = topLevelChar(piece, '=');
            if (eq < 0) continue;
            String col = unquoteIdent(piece.substring(0, eq).trim());
            String val = literal(piece.substring(eq + 1).trim());
            if (!col.isBlank()) out.put(col, val);
        }
        return out;
    }

    /** Split respecting single-quoted strings ('' escape) and parenthesis nesting. */
    static List<String> splitTopLevel(String s, String delim) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q) {
                if (ch == '\'') { if (i + 1 < s.length() && s.charAt(i + 1) == '\'') i++; else q = false; }
                continue;
            }
            if (ch == '\'') { q = true; continue; }
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (depth == 0 && s.regionMatches(true, i, delim, 0, delim.length())) {
                out.add(s.substring(start, i));
                i += delim.length() - 1;
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static int topLevelChar(String s, char target) {
        boolean q = false; int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q) { if (ch == '\'') { if (i + 1 < s.length() && s.charAt(i + 1) == '\'') i++; else q = false; } continue; }
            if (ch == '\'') { q = true; continue; }
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (depth == 0 && ch == target) return i;
        }
        return -1;
    }

    private static int indexOfKeyword(String lower, String kw, int from) {
        boolean q = false;
        for (int i = Math.max(0, from); i + kw.length() <= lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch == '\'') q = !q;
            if (!q && lower.startsWith(kw, i)) return i;
        }
        return -1;
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        boolean q = false;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (q) { if (ch == '\'') { if (i + 1 < s.length() && s.charAt(i + 1) == '\'') i++; else q = false; } continue; }
            if (ch == '\'') { q = true; }
            else if (ch == '(') depth++;
            else if (ch == ')') { depth--; if (depth == 0) return i; }
        }
        return s.length();
    }

    static String unquoteIdent(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }

    /** Normalise a redo value literal: strip quotes, unescape, map NULL to null, keep others raw. */
    static String literal(String v) {
        v = v.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("NULL")) return null;
        if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        return v;
    }

    // ------------------------------------------------------------------ PK lookup

    private void attachPk(DecodedChange ch, Map<String, List<String>> cache, DataSourceEntity ds) {
        String key = ch.schema + "." + ch.table;
        List<String> pk = cache.computeIfAbsent(key, k -> loadPk(ds, ch.schema, ch.table));
        for (String col : pk) if (ch.values.containsKey(col)) ch.pk.put(col, ch.values.get(col));
    }

    private List<String> loadPk(DataSourceEntity ds, String owner, String table) {
        List<String> pk = new ArrayList<>();
        String sql = "SELECT cc.COLUMN_NAME FROM ALL_CONSTRAINTS ct "
                + "JOIN ALL_CONS_COLUMNS cc ON cc.CONSTRAINT_NAME = ct.CONSTRAINT_NAME AND cc.OWNER = ct.OWNER "
                + "WHERE ct.CONSTRAINT_TYPE = 'P' AND ct.OWNER = ? AND ct.TABLE_NAME = ? ORDER BY cc.POSITION";
        try (Connection c = open(ds); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) pk.add(rs.getString(1)); }
        } catch (Exception ignored) { /* best effort */ }
        return pk;
    }

    // ------------------------------------------------------------------ helpers

    private Connection open(DataSourceEntity ds) throws Exception {
        Properties p = new Properties();
        if (ds.getUsername() != null) p.setProperty("user", ds.getUsername());
        if (ds.getPassword() != null) p.setProperty("password", ds.getPassword());
        return DriverManager.getConnection(ds.getJdbcUrl(), p);
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String safeScn(String s, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        String t = s.trim();
        return t.matches("\\d+") ? t : fallback;
    }

    private static String safeIdent(String s) {
        return s == null ? "<user>" : s.replaceAll("[^A-Za-z0-9_$#]", "");
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.toString() : r.getMessage();
    }
}
