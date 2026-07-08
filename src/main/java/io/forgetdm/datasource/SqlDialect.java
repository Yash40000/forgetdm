package io.forgetdm.datasource;

import java.sql.Connection;
import java.util.Locale;
import java.util.Set;

/**
 * SQL dialect awareness for registered external databases.
 * Supported: POSTGRES, H2, MYSQL, DB2 / DB2UDB (LUW + z/OS), ORACLE, SQLSERVER, GENERIC.
 *
 * Most of ForgeTDM's SQL is ANSI-portable (double-quoted identifiers, JDBC metadata,
 * Statement.setMaxRows). This enum concentrates the few spots that genuinely differ:
 * dialect detection, system-schema filtering, and TRUNCATE syntax.
 */
public enum SqlDialect {
    POSTGRES, H2, MYSQL, DB2, ORACLE, SQLSERVER, GENERIC;

    /** Resolve from the registered kind first, falling back to the JDBC URL prefix. */
    public static SqlDialect of(DataSourceEntity ds) {
        if (ds != null && ds.getKind() != null) {
            switch (ds.getKind().trim().toUpperCase(Locale.ROOT)) {
                case "POSTGRES", "POSTGRESQL": return POSTGRES;
                case "H2": return H2;
                case "MYSQL", "MARIADB": return MYSQL;
                case "DB2", "DB2UDB", "DB2_UDB", "DB2LUW", "DB2ZOS": return DB2;
                case "ORACLE": return ORACLE;
                case "SQLSERVER", "SQL_SERVER", "MSSQL": return SQLSERVER;
                default: break; // GENERIC or unknown -> try URL
            }
        }
        return fromUrl(ds == null ? null : ds.getJdbcUrl());
    }

    public static SqlDialect fromUrl(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:postgresql:")) return POSTGRES;
        if (u.startsWith("jdbc:h2:")) return H2;
        if (u.startsWith("jdbc:mysql:") || u.startsWith("jdbc:mariadb:")) return MYSQL;
        if (u.startsWith("jdbc:db2:")) return DB2;
        if (u.startsWith("jdbc:oracle:")) return ORACLE;
        if (u.startsWith("jdbc:sqlserver:")) return SQLSERVER;
        return GENERIC;
    }

    /** Resolve from a live connection's metadata (for code paths that only hold a Connection). */
    public static SqlDialect fromConnection(Connection c) {
        try {
            String p = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (p.contains("postgres")) return POSTGRES;
            if (p.contains("h2")) return H2;
            if (p.contains("mysql") || p.contains("mariadb")) return MYSQL;
            if (p.contains("db2")) return DB2;
            if (p.contains("oracle")) return ORACLE;
            if (p.contains("microsoft sql server") || p.contains("sql server")) return SQLSERVER;
        } catch (Exception ignored) { }
        return GENERIC;
    }

    /** One TRUNCATE statement per table; DB2 requires IMMEDIATE, all others use plain syntax. */
    public String truncateSql(String qualifiedTable) {
        return this == DB2
                ? "TRUNCATE TABLE " + qualifiedTable + " IMMEDIATE"
                : "TRUNCATE TABLE " + qualifiedTable;
    }

    /**
     * Postgres allows TRUNCATE a, b, c in one statement (atomic across FK-related tables).
     * MySQL/MariaDB, DB2, Oracle, SQL Server, and H2 do not support this syntax.
     */
    public boolean supportsMultiTableTruncate() { return this == POSTGRES; }

    /**
     * True for engines that accept a multi-row VALUES constructor in a single INSERT
     * (INSERT INTO t (..) VALUES (..),(..),(..)). This is a portable bulk-load fast path — far fewer
     * round-trips than per-row INSERT. Oracle and unknown/GENERIC engines do not support it (Oracle uses
     * INSERT ALL), so they fall back to JDBC array batching, which is already their native fast path.
     */
    public boolean supportsMultiRowInsert() {
        return this == POSTGRES || this == MYSQL || this == SQLSERVER || this == H2 || this == DB2;
    }

    /** Max rows allowed in one multi-row VALUES INSERT. SQL Server caps the row constructor at 1000. */
    public int maxRowsPerInsert() {
        return this == SQLSERVER ? 1000 : 10_000;
    }

    /** Max bind parameters per statement (drivers reject statements that exceed this). */
    public int bindParamLimit() {
        return switch (this) {
            case SQLSERVER -> 2100;     // T-SQL / JDBC driver hard limit
            case POSTGRES  -> 65_535;   // Int16 parameter count in the wire protocol
            default        -> 32_767;   // conservative for MySQL/DB2/H2
        };
    }

    /**
     * True for engines where TRUNCATE (and DDL generally) performs an implicit COMMIT, so it cannot be
     * rolled back if a subsequent load fails. On these, a REPLACE should clear with transactional DELETE
     * instead of TRUNCATE to avoid leaving the target emptied after a failed job.
     */
    public boolean ddlAutoCommits() { return this == ORACLE || this == DB2 || this == MYSQL; }

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            // ANSI / shared
            "information_schema",
            // Postgres
            "pg_catalog",
            // H2
            "sys", "system_lobs",
            // DB2 / DB2 UDB
            "syscat", "sysibm", "sysibmadm", "sysibmts", "sysibminternal", "sysstat",
            "sysproc", "sysfun", "systools", "syspublic", "nullid", "sqlj",
            // Oracle (common locked/system accounts)
            "system", "sysaux", "outln", "xdb", "ctxsys", "mdsys", "ordsys", "orddata",
            "ordplugins", "olapsys", "lbacsys", "wmsys", "dvsys", "dvf", "dbsnmp",
            "appqossys", "gsmadmin_internal", "gsmcatuser", "gsmuser", "ojvmsys",
            "anonymous", "audsys", "dbsfwuser", "remote_scheduler_agent", "dip",
            "oracle_ocm", "xs$null", "mddata", "sys$umf",
            // SQL Server
            "guest", "db_owner", "db_accessadmin", "db_securityadmin", "db_ddladmin",
            "db_backupoperator", "db_datareader", "db_datawriter",
            "db_denydatareader", "db_denydatawriter");

    /** True for catalog/system schemas of any supported DB (Postgres, H2, DB2, Oracle, SQL Server). */
    public static boolean isSystemSchema(String schema) {
        if (schema == null) return true;
        String s = schema.toLowerCase(Locale.ROOT);
        return SYSTEM_SCHEMAS.contains(s)
                || s.startsWith("pg_")        // Postgres pg_toast, pg_temp_*, ...
                || s.startsWith("apex_")      // Oracle APEX
                || s.startsWith("flows_");    // legacy Oracle APEX
    }

    /** Skip vendor housekeeping tables, e.g. Oracle recycle-bin (BIN$...) and DB2 explain tables. */
    public static boolean isSystemTable(String table) {
        return table == null || table.startsWith("BIN$");
    }
}
