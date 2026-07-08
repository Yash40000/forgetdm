package io.forgetdm.provision;

import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.core.util.SeedLists;
import io.forgetdm.datasource.SqlDialect;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Database-side ("pushdown") equivalents of a SAFE subset of ForgeTDM masks, so the mask is computed entirely
 * inside the target database — no rows leave the server — for the columns it supports. This is the GenRocket-style
 * "generators have DB-side equivalents" throughput path.
 *
 * <h3>Hard rule: byte-identical output</h3>
 * A DB-side function MUST produce exactly the same masked value as the Java engine for the same input — otherwise
 * a value masked one way in table A (Java) and another in table B (SQL) silently breaks referential integrity.
 * Every function installed here is therefore <b>parity-verified at runtime</b> against {@link MaskingEngine}
 * before it is used; if it does not match (e.g. an engine without native HMAC, or a missing extension), pushdown
 * is simply not offered for that function and the caller keeps using the (parallel) Java path. So this can never
 * corrupt data — at worst it provides no speedup.
 *
 * <h3>Scope (phase 1)</h3>
 * Postgres only (native {@code pgcrypto.hmac}); the single-draw, seed-list generators with default casing:
 * FIRST_NAME, LAST_NAME, COMPANY, HASH_LOV. These are {@code seedlist[hashLong(secret, salt|seedlist,
 * normalize(value)) % size]} — a pure hash + table lookup. Multi-draw masks (FPE, SSN, phone, credit card,
 * address) depend on {@code java.util.Random} draws and stay on the Java path by design. Other engines fall
 * back until their (hand-rolled HMAC) functions are added and pass parity.
 *
 * <h3>Security tradeoff</h3>
 * Computing keyed masks in the database requires the masking secret to exist there (baked into the installed
 * function). That weakens the "key never stored with the data" property, so this whole path is OFF unless
 * {@code FORGETDM_DB_PUSHDOWN=true}. Installed objects use a random per-session suffix and are dropped in
 * {@link Session#cleanup()} as soon as the job finishes, minimising exposure.
 */
@Component
public class DbMaskPushdown {

    private static final SecureRandom RAND = new SecureRandom();
    /** Representative parity samples (incl. odd casing / spacing); plus null/empty handled structurally. */
    private static final String[] SAMPLE_VALUES = {"John", "MARÍA", "o'brien", "  smith  ", "Acme Corp", "z"};
    private static final String[] SAMPLE_SALTS = {"customers.first_name", "ri:root", "x"};

    public boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv("FORGETDM_DB_PUSHDOWN"));
    }

    /**
     * Install + parity-verify the pushdown helpers for the given seedlists on {@code out}. Returns a ready
     * {@link Session}, or {@code null} if pushdown is disabled, the engine is unsupported, the crypto primitive
     * can't be verified, or no seedlist passes parity. Safe to call once per job on the main connection; the
     * created objects are committed so parallel worker connections can use them.
     */
    public Session prepare(Connection out, SqlDialect dialect, MaskingEngine eng,
                           String schema, Set<String> seedlistsNeeded) {
        if (!enabled() || dialect != SqlDialect.POSTGRES || seedlistsNeeded == null || seedlistsNeeded.isEmpty())
            return null;
        String secret = eng.pushdownKey();
        String suffix = Long.toHexString(RAND.nextLong() & 0x7fffffffffffffffL);
        String sch = (schema == null || schema.isBlank()) ? "" : "\"" + schema.replace("\"", "\"\"") + "\".";
        String fn = sch + "\"forgetdm_hashlong_" + suffix + "\"";
        Session s = new Session(out, fn, suffix, sch);
        try {
            try (Statement st = out.createStatement()) {
                st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
                st.execute(
                    "CREATE FUNCTION " + fn + "(saltkey text, val text) RETURNS bigint AS $FDM$ " +
                    "SELECT ( ('x' || encode(substring( hmac( " +
                    "  convert_to(saltkey,'UTF8') || E'\\x1f'::bytea || convert_to(coalesce(val,''),'UTF8'), " +
                    "  convert_to(" + sqlStr(secret) + ",'UTF8'), 'sha256') from 1 for 8), 'hex'))::bit(64)::bigint ) " +
                    "& 9223372036854775807 $FDM$ LANGUAGE sql IMMUTABLE");
            }
            out.commit();
            if (!verifyHashLong(out, fn, secret)) { rollbackQuietly(out); s.cleanup(); return null; }   // crypto parity gate

            for (String seedlist : seedlistsNeeded) {
                try {
                    String table = installSeedTable(out, sch, suffix, seedlist);
                    if (verifyGenerator(out, fn, table, seedlist, eng)) { s.eligibleSeed.put(seedlist, table); out.commit(); }
                    else rollbackQuietly(out);   // undo this table + clear any aborted tx; other seedlists unaffected
                } catch (Exception ignore) {
                    rollbackQuietly(out);   // isolate a bad seedlist so it can't poison the rest
                }
            }
            if (s.eligibleSeed.isEmpty()) { s.cleanup(); return null; }
            return s;
        } catch (Exception e) {
            rollbackQuietly(out);
            s.cleanup();
            return null;   // never let an optimization break the job
        }
    }

    private static void rollbackQuietly(Connection c) {
        try { c.rollback(); } catch (SQLException ignore) { /* best effort */ }
    }

    /** True if a (function, params) pair is a single-pick generator with default casing — the only pushdownable case. */
    public boolean isPushdownable(MaskFunction fn, String p1, String p2) {
        return seedlistFor(fn, p1, p2) != null;
    }

    /** The seedlist a single-pick generator draws from, or null if this mask isn't a pushdownable generator. */
    public String seedlistFor(MaskFunction fn, String p1, String p2) {
        if (fn == null) return null;
        return switch (fn) {
            case FIRST_NAME -> caseMode(p1, p2) == null ? "first_names.txt" : null;
            case LAST_NAME  -> caseMode(p1, p2) == null ? "last_names.txt"  : null;
            case COMPANY    -> caseMode(p1, p2) == null ? "companies.txt"   : null;
            case HASH_LOV   -> caseMode(null, p2) == null ? (p1 == null ? "first_names.txt" : p1) : null;
            default -> null;
        };
    }

    // ----- per-job session -----

    public final class Session {
        private final Connection installConn;
        private final String fn;
        private final String suffix;
        private final String sch;
        private final Map<String, String> eligibleSeed = new HashMap<>();   // seedlist -> qualified table name

        private Session(Connection installConn, String fn, String suffix, String sch) {
            this.installConn = installConn; this.fn = fn; this.suffix = suffix; this.sch = sch;
        }

        /**
         * SQL assignment {@code "col" = <expr>} that masks the column entirely in-DB, or null if this mask isn't a
         * verified pushdownable generator. {@code columnSalt} is the exact salt the Java engine would use for this
         * column (computed by the caller), embedded as a constant so the keyed hash matches.
         */
        public String assignment(String quotedCol, MaskFunction fn0, String p1, String p2, String columnSalt) {
            String seedlist = seedlistFor(fn0, p1, p2);
            if (seedlist == null) return null;
            String table = eligibleSeed.get(seedlist);
            if (table == null) return null;
            int size = SeedLists.get(seedlist).size();
            String saltkey = (columnSalt == null ? "" : columnSalt) + "|" + seedlist;
            // Mirror MaskingEngine: null/empty pass through unchanged; otherwise seedlist[hashLong(salt, norm(v)) % n].
            String idx = fn + "(" + sqlStr(saltkey) + ", lower(btrim(" + quotedCol + "))) % " + size;
            String lookup = "(SELECT s.val FROM " + table + " s WHERE s.idx = " + idx + ")";
            return quotedCol + " = CASE WHEN " + quotedCol + " IS NULL OR " + quotedCol + " = '' THEN "
                    + quotedCol + " ELSE " + lookup + " END";
        }

        public boolean hasAny() { return !eligibleSeed.isEmpty(); }

        /** Drop every installed object — crucially removes the function carrying the secret. Best-effort. */
        public void cleanup() {
            for (String table : new ArrayList<>(eligibleSeed.values())) dropQuietly(installConn, table);
            try (Statement st = installConn.createStatement()) {
                st.execute("DROP FUNCTION IF EXISTS " + fn + "(text, text)");
            } catch (SQLException ignore) { /* best effort */ }
            try { installConn.commit(); } catch (SQLException ignore) { /* best effort */ }
        }
    }

    // ----- install / verify -----

    private String installSeedTable(Connection out, String sch, String suffix, String seedlist) throws SQLException {
        List<String> values = SeedLists.get(seedlist);
        String table = sch + "\"forgetdm_seed_" + sanitize(seedlist) + "_" + suffix + "\"";
        try (Statement st = out.createStatement()) {
            st.execute("CREATE TABLE " + table + " (idx integer PRIMARY KEY, val text NOT NULL)");
        }
        try (PreparedStatement ps = out.prepareStatement("INSERT INTO " + table + " (idx, val) VALUES (?, ?)")) {
            for (int i = 0; i < values.size(); i++) { ps.setInt(1, i); ps.setString(2, values.get(i)); ps.addBatch(); }
            ps.executeBatch();
        }
        return table;
    }

    private boolean verifyHashLong(Connection out, String fn, String secret) {
        try (PreparedStatement ps = out.prepareStatement("SELECT " + fn + "(?, ?)")) {
            for (String salt : SAMPLE_SALTS) for (String val : SAMPLE_VALUES) {
                ps.setString(1, salt); ps.setString(2, val);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    long db = rs.getLong(1);
                    long java = io.forgetdm.core.util.Determinism.hashLong(secret, salt, val);
                    if (db != java) return false;
                }
            }
            return true;
        } catch (SQLException e) { return false; }
    }

    /** Parity-check a generator across sample (salt, value) pairs: DB lookup must equal MaskingEngine output. */
    private boolean verifyGenerator(Connection out, String fn, String table, String seedlist, MaskingEngine eng) {
        int size = SeedLists.get(seedlist).size();
        MaskFunction mf = seedlist.equals("first_names.txt") ? MaskFunction.FIRST_NAME
                : seedlist.equals("last_names.txt") ? MaskFunction.LAST_NAME
                : seedlist.equals("companies.txt") ? MaskFunction.COMPANY : MaskFunction.HASH_LOV;
        String p1 = mf == MaskFunction.HASH_LOV ? seedlist : null;
        String sql = "SELECT (SELECT s.val FROM " + table + " s WHERE s.idx = "
                + fn + "(?, lower(btrim(?))) % " + size + ")";
        try (PreparedStatement ps = out.prepareStatement(sql)) {
            for (String salt : SAMPLE_SALTS) for (String val : SAMPLE_VALUES) {
                ps.setString(1, salt + "|" + seedlist); ps.setString(2, val);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    String db = rs.getString(1);
                    String java = eng.mask(mf, salt, val, p1, null, null);
                    if (java == null ? db != null : !java.equals(db)) return false;
                }
            }
            return true;
        } catch (SQLException e) { return false; }
    }

    private static void dropQuietly(Connection c, String table) {
        try (Statement st = c.createStatement()) { st.execute("DROP TABLE IF EXISTS " + table); }
        catch (SQLException ignore) { /* best effort */ }
    }

    // ----- helpers (mirror MaskingEngine's casing detection so we only push the default-case path) -----

    private static String caseMode(String p1, String p2) {
        if (isCaseMode(p2)) return p2.trim().toUpperCase(Locale.ROOT);
        if (isCaseMode(p1)) return p1.trim().toUpperCase(Locale.ROOT);
        return null;
    }

    private static boolean isCaseMode(String mode) {
        if (mode == null || mode.isBlank()) return false;
        String m = mode.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        return m.equals("LOWER") || m.equals("LOWERCASE") || m.equals("UPPER") || m.equals("UPPERCASE")
                || m.equals("PROPER") || m.equals("TITLE") || m.equals("TITLE_CASE")
                || m.equals("AS_IS") || m.equals("PRESERVE") || m.equals("ORIGINAL");
    }

    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray())
            b.append(Character.isLetterOrDigit(c) ? c : '_');
        return b.toString();
    }

    /** Single-quoted SQL string literal with '' escaping (used for constants we must embed, e.g. the salt). */
    private static String sqlStr(String s) {
        return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
    }
}
