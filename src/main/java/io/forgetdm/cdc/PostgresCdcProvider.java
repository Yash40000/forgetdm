package io.forgetdm.cdc;

import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * True CDC for PostgreSQL via logical replication.
 *
 * Uses a logical replication slot with the built-in {@code test_decoding} output plugin
 * (present on every stock Postgres, so no server-side extension is required). Each poll
 * resumes from the last confirmed LSN, decodes only the WAL records written since then,
 * and flushes the new LSN back so the server can advance the slot and reclaim WAL. No
 * table is re-read — only rows actually changed are emitted.
 */
@Component
public class PostgresCdcProvider implements CdcProvider {

    @Override
    public boolean supports(DataSourceEntity ds) {
        String url = ds.getJdbcUrl() == null ? "" : ds.getJdbcUrl().toLowerCase(Locale.ROOT);
        String kind = ds.getKind() == null ? "" : ds.getKind().toLowerCase(Locale.ROOT);
        return url.startsWith("jdbc:postgresql:") || kind.contains("postgres");
    }

    @Override
    public String mechanism() { return "PostgreSQL logical replication (test_decoding)"; }

    @Override
    public String pluginName() { return "test_decoding"; }

    // ------------------------------------------------------------------ preflight

    @Override
    public Preflight preflight(DataSourceEntity ds) {
        List<String> messages = new ArrayList<>();
        String walLevel = "unknown";
        boolean privileged = false;
        try (Connection c = openNormal(ds)) {
            walLevel = scalar(c, "SHOW wal_level");
            if (!"logical".equalsIgnoreCase(walLevel)) {
                messages.add("wal_level is '" + walLevel + "'. Set 'wal_level = logical' in postgresql.conf "
                        + "(or 'ALTER SYSTEM SET wal_level = logical;') and restart the server.");
            }
            String priv = scalar(c, "SELECT (rolsuper OR rolreplication)::text FROM pg_roles WHERE rolname = current_user");
            privileged = "true".equalsIgnoreCase(priv);
            if (!privileged) {
                messages.add("Role '" + ds.getUsername() + "' lacks REPLICATION. Grant it: "
                        + "'ALTER ROLE " + safeIdent(ds.getUsername()) + " WITH REPLICATION;'.");
            }
            int maxSlots = intScalar(c, "SHOW max_replication_slots");
            int usedSlots = intScalar(c, "SELECT count(*) FROM pg_replication_slots");
            if (maxSlots > 0 && usedSlots >= maxSlots) {
                messages.add("All " + maxSlots + " replication slots are in use. Raise max_replication_slots "
                        + "or drop an unused slot.");
            }
            // Replication connections must be permitted in pg_hba.conf; surface a hint proactively.
            messages.add("Ensure pg_hba.conf permits replication connections for this user/host "
                    + "(a 'replication' database entry).");
        } catch (Exception e) {
            throw ApiException.bad("CDC preflight failed: " + rootMessage(e));
        }
        boolean ok = "logical".equalsIgnoreCase(walLevel) && privileged;
        return new Preflight(ok, walLevel, privileged, messages);
    }

    @Override
    public String currentLogPosition(DataSourceEntity ds) {
        try (Connection c = openNormal(ds)) {
            return scalar(c, "SELECT pg_current_wal_lsn()::text");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Long lag(DataSourceEntity ds, String confirmedPosition) {
        if (confirmedPosition == null || confirmedPosition.isBlank()) return null;
        try (Connection c = openNormal(ds); PreparedStatement ps =
                c.prepareStatement("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)")) {
            ps.setString(1, confirmedPosition);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String lagUnit() { return "bytes"; }

    // ------------------------------------------------------------------ slot mgmt

    @Override
    public SlotInfo createSlot(DataSourceEntity ds, String slotName) {
        try (Connection c = openNormal(ds)) {
            SlotInfo existing = readSlot(c, slotName);
            if (existing != null) return existing;
        } catch (Exception e) {
            throw ApiException.bad("Cannot inspect replication slots: " + rootMessage(e));
        }
        // Create on a replication connection, then read back the durable LSNs.
        try (Connection repl = openReplication(ds)) {
            PGConnection pg = repl.unwrap(PGConnection.class);
            pg.getReplicationAPI()
                    .createReplicationSlot()
                    .logical()
                    .withSlotName(slotName)
                    .withOutputPlugin("test_decoding")
                    .make();
        } catch (Exception e) {
            throw ApiException.bad("Failed to create replication slot '" + slotName + "': " + rootMessage(e));
        }
        try (Connection c = openNormal(ds)) {
            SlotInfo info = readSlot(c, slotName);
            if (info == null) throw ApiException.bad("Slot '" + slotName + "' was created but could not be read back.");
            return info;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Cannot read new slot: " + rootMessage(e)); }
    }

    @Override
    public void dropSlot(DataSourceEntity ds, String slotName) {
        try (Connection c = openNormal(ds)) {
            // Terminate any walsender still holding the slot so the drop cannot fail as "in use".
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pg_terminate_backend(active_pid) FROM pg_replication_slots "
                            + "WHERE slot_name = ? AND active_pid IS NOT NULL")) {
                ps.setString(1, slotName);
                ps.execute();
            } catch (Exception ignored) { /* best effort */ }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = ?")) {
                ps.setString(1, slotName);
                ps.execute();
            }
        } catch (Exception e) {
            throw ApiException.bad("Failed to drop replication slot '" + slotName + "': " + rootMessage(e));
        }
    }

    // ------------------------------------------------------------------ poll

    @Override
    public PollResult poll(DataSourceEntity ds, CdcCaptureEntity capture, int maxChanges, long budgetMillis) {
        String slot = capture.getSlotName();
        Map<String, List<String>> pkCache = new LinkedHashMap<>();
        List<DecodedChange> out = new ArrayList<>();
        String confirmed = capture.getConfirmedLsn();
        boolean reachedEnd = false;

        try (Connection repl = openReplication(ds)) {
            PGConnection pg = repl.unwrap(PGConnection.class);
            var builder = pg.getReplicationAPI().replicationStream().logical()
                    .withSlotName(slot)
                    .withSlotOption("include-xids", true)
                    .withSlotOption("skip-empty-xacts", true)
                    .withStatusInterval(10, TimeUnit.SECONDS);
            LogSequenceNumber start = parseLsn(capture.getConfirmedLsn());
            if (start != null) builder = builder.withStartPosition(start);

            PGReplicationStream stream = builder.start();
            long deadline = System.currentTimeMillis() + Math.max(250, budgetMillis);
            LogSequenceNumber last = start;
            Long curXid = null;

            while (out.size() < maxChanges && System.currentTimeMillis() < deadline) {
                ByteBuffer buf = stream.readPending();
                if (buf == null) {
                    reachedEnd = true;
                    if (!out.isEmpty()) break;         // drained a batch — stop, don't idle
                    Thread.sleep(20);
                    continue;
                }
                reachedEnd = false;
                String line = decode(buf);
                LogSequenceNumber lsn = stream.getLastReceiveLSN();
                last = lsn;

                if (line.startsWith("BEGIN")) { curXid = tailLong(line); continue; }
                if (line.startsWith("COMMIT")) { curXid = null; continue; }

                DecodedChange ch = parseChangeLine(line);
                if (ch == null) continue;
                ch.lsn = lsn == null ? null : lsn.asString();
                ch.xid = curXid;
                attachPk(ch, pkCache, ds);
                out.add(ch);
            }

            // Confirm/flush so the server advances the slot and reclaims WAL.
            if (last != null) {
                stream.setAppliedLSN(last);
                stream.setFlushedLSN(last);
                stream.forceUpdateStatus();
                confirmed = last.asString();
            }
            stream.close();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("CDC poll failed for slot '" + slot + "': " + rootMessage(e));
        }
        return new PollResult(out, confirmed, reachedEnd);
    }

    // ------------------------------------------------------------------ connections

    private Connection openNormal(DataSourceEntity ds) throws Exception {
        Properties p = credentials(ds);
        return DriverManager.getConnection(ds.getJdbcUrl(), p);
    }

    private Connection openReplication(DataSourceEntity ds) throws Exception {
        Properties p = credentials(ds);
        PGProperty.REPLICATION.set(p, "database");
        PGProperty.ASSUME_MIN_SERVER_VERSION.set(p, "9.4");
        PGProperty.PREFER_QUERY_MODE.set(p, "simple");
        return DriverManager.getConnection(ds.getJdbcUrl(), p);
    }

    private Properties credentials(DataSourceEntity ds) {
        Properties p = new Properties();
        if (ds.getUsername() != null) p.setProperty("user", ds.getUsername());
        if (ds.getPassword() != null) p.setProperty("password", ds.getPassword());
        return p;
    }

    // ------------------------------------------------------------------ slot readback

    private SlotInfo readSlot(Connection c, String slotName) throws Exception {
        String sql = "SELECT restart_lsn::text, confirmed_flush_lsn::text FROM pg_replication_slots WHERE slot_name = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, slotName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SlotInfo(slotName, rs.getString(1), rs.getString(2));
            }
        }
    }

    // ------------------------------------------------------------------ PK lookup

    private void attachPk(DecodedChange ch, Map<String, List<String>> cache, DataSourceEntity ds) {
        String key = ch.schema + "." + ch.table;
        List<String> pk = cache.computeIfAbsent(key, k -> loadPk(ds, ch.schema, ch.table));
        for (String col : pk) {
            if (ch.values.containsKey(col)) ch.pk.put(col, ch.values.get(col));
        }
    }

    private List<String> loadPk(DataSourceEntity ds, String schema, String table) {
        List<String> pk = new ArrayList<>();
        String sql = "SELECT a.attname FROM pg_index i "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE i.indrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass AND i.indisprimary "
                + "ORDER BY array_position(i.indkey, a.attnum)";
        try (Connection c = openNormal(ds); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pk.add(rs.getString(1));
            }
        } catch (Exception ignored) { /* PK is best-effort */ }
        return pk;
    }

    // ------------------------------------------------------------------ test_decoding parsing

    /**
     * Parse a single test_decoding row line into a change. Returns null for lines that are
     * not row changes. Handles default and FULL replica identity (old-key / new-tuple).
     *
     * Examples:
     *   table public.accounts: INSERT: id[integer]:5 name[character varying]:'John'
     *   table public.accounts: UPDATE: id[integer]:5 name[character varying]:'Jane'
     *   table public.accounts: DELETE: id[integer]:5
     *   table public.accounts: UPDATE: old-key: id[integer]:5 new-tuple: id[integer]:5 name[...]:'Jane'
     */
    static DecodedChange parseChangeLine(String line) {
        if (line == null || !line.startsWith("table ")) return null;
        String rest = line.substring("table ".length());
        int nameEnd = topLevelIndex(rest, ": ");
        if (nameEnd < 0) return null;
        String qualified = rest.substring(0, nameEnd).trim();
        String after = rest.substring(nameEnd + 2);
        int opEnd = after.indexOf(':');
        if (opEnd < 0) return null;
        String opWord = after.substring(0, opEnd).trim().toUpperCase(Locale.ROOT);
        String body = after.substring(opEnd + 1).trim();

        DecodedChange ch = new DecodedChange();
        String[] st = splitQualified(qualified);
        ch.schema = st[0];
        ch.table = st[1];
        ch.op = switch (opWord) {
            case "INSERT" -> "I";
            case "UPDATE" -> "U";
            case "DELETE" -> "D";
            default -> null;
        };
        if (ch.op == null) return null;

        // FULL replica identity splits old-key / new-tuple.
        int nt = body.indexOf("new-tuple:");
        if (nt >= 0) {
            body = body.substring(nt + "new-tuple:".length()).trim();
        } else if (body.startsWith("old-key:")) {
            body = body.substring("old-key:".length()).trim();
        }
        ch.values = parseTuples(body);
        return ch;
    }

    /** Split "schema.table" honoring double-quoted identifiers. */
    static String[] splitQualified(String q) {
        int dot = topLevelIndex(q, ".");
        String schema = dot < 0 ? "public" : q.substring(0, dot);
        String table = dot < 0 ? q : q.substring(dot + 1);
        return new String[]{ unquoteIdent(schema), unquoteIdent(table) };
    }

    static String unquoteIdent(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }

    /** Index of {@code token} in {@code s} ignoring occurrences inside double quotes. */
    static int topLevelIndex(String s, String token) {
        boolean inQuote = false;
        for (int i = 0; i + token.length() <= s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            if (!inQuote && s.startsWith(token, i)) return i;
        }
        return -1;
    }

    /**
     * Parse {@code col[type]:value col2[type]:value} tuples. Values may be single-quoted
     * strings (with '' escaping) or bare tokens (numbers, booleans, or the literal null).
     */
    static Map<String, String> parseTuples(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && s.charAt(i) == ' ') i++;
            if (i >= n) break;

            // column name (possibly double-quoted), terminated by '['
            StringBuilder name = new StringBuilder();
            if (s.charAt(i) == '"') {
                i++;
                while (i < n) {
                    char c = s.charAt(i++);
                    if (c == '"') { if (i < n && s.charAt(i) == '"') { name.append('"'); i++; } else break; }
                    else name.append(c);
                }
            } else {
                while (i < n && s.charAt(i) != '[') name.append(s.charAt(i++));
            }
            if (i >= n || s.charAt(i) != '[') break;
            // type: read to matching ']'
            i++; // skip '['
            int depth = 1;
            while (i < n && depth > 0) {
                char c = s.charAt(i++);
                if (c == '[') depth++;
                else if (c == ']') depth--;
            }
            if (i >= n || s.charAt(i) != ':') break;
            i++; // skip ':'

            // value: quoted string or bare token
            String value;
            if (i < n && s.charAt(i) == '\'') {
                i++;
                StringBuilder v = new StringBuilder();
                while (i < n) {
                    char c = s.charAt(i++);
                    if (c == '\'') { if (i < n && s.charAt(i) == '\'') { v.append('\''); i++; } else break; }
                    else v.append(c);
                }
                value = v.toString();
            } else {
                StringBuilder v = new StringBuilder();
                while (i < n && s.charAt(i) != ' ') v.append(s.charAt(i++));
                String bare = v.toString();
                value = "null".equals(bare) ? null : bare;
            }
            out.put(name.toString().trim(), value);
        }
        return out;
    }

    // ------------------------------------------------------------------ small helpers

    private static String decode(ByteBuffer buf) {
        int offset = buf.arrayOffset();
        byte[] source = buf.array();
        int length = source.length - offset;
        return new String(source, offset, length, StandardCharsets.UTF_8);
    }

    private static LogSequenceNumber parseLsn(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            LogSequenceNumber lsn = LogSequenceNumber.valueOf(s.trim());
            return lsn == LogSequenceNumber.INVALID_LSN ? null : lsn;
        } catch (Exception e) { return null; }
    }

    private static Long tailLong(String line) {
        int sp = line.lastIndexOf(' ');
        if (sp < 0) return null;
        try { return Long.parseLong(line.substring(sp + 1).trim()); } catch (Exception e) { return null; }
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int intScalar(Connection c, String sql) {
        try { String v = scalar(c, sql); return v == null ? 0 : Integer.parseInt(v.trim()); }
        catch (Exception e) { return 0; }
    }

    private static String safeIdent(String s) {
        return s == null ? "<user>" : s.replaceAll("[^A-Za-z0-9_]", "");
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.toString() : r.getMessage();
    }
}
