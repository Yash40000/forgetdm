package io.forgetdm.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.forgetdm.common.ApiException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens JDBC connections to user-registered external databases (sources/targets).
 *
 * Two paths:
 *  - {@link #openPooled}: small per-datasource HikariCP pool for short interactive calls
 *    (schema/table/column/FK browsing, discovery sampling, plan previews, query console).
 *    Skips the per-request connect/auth handshake that dominates metadata latency.
 *  - {@link #open}/{@link #openForBulk}: direct unpooled connections for everything held long
 *    or that mutates unresettable session state (provisioning loads, virtualization ingest,
 *    Oracle ALTER SESSION tuning). These must never occupy or taint pool slots.
 */
@Component
public class ConnectionFactory implements DisposableBean {
    private static final int LOGIN_TIMEOUT_SECONDS = intEnv("FORGETDM_JDBC_LOGIN_TIMEOUT_SECONDS", 8);
    private static final int READ_TIMEOUT_SECONDS = intEnv("FORGETDM_JDBC_READ_TIMEOUT_SECONDS", 45);
    // Bulk loads (synthetic generation, provisioning) legitimately run far longer than an interactive query:
    // big batches/COPY, TRUNCATE CASCADE, and post-load FK-orphan validation joins. A short socket read timeout
    // would abort them as "Read timed out". 0 = no socket read timeout (rely on job cancel + statement cancel).
    private static final int LOAD_READ_TIMEOUT_SECONDS = intEnv("FORGETDM_JDBC_LOAD_READ_TIMEOUT_SECONDS", 0);
    // Interactive pool: small, and drains to zero when idle so we never hold sockets open
    // against a user's database after they stop browsing the console.
    private static final int POOL_MAX_CONNECTIONS = intEnv("FORGETDM_JDBC_POOL_MAX", 4);
    private static final int POOL_IDLE_TIMEOUT_SECONDS = intEnv("FORGETDM_JDBC_POOL_IDLE_SECONDS", 60);

    private record Pool(String signature, HikariDataSource ds) {}
    private final Map<Long, Pool> pools = new ConcurrentHashMap<>();

    /** Interactive connection: short read timeout so metadata browsing fails fast on a slow/dead server. */
    public Connection open(DataSourceEntity ds) {
        return open(ds, READ_TIMEOUT_SECONDS);
    }

    /**
     * Pooled interactive connection for short metadata/browsing calls. Callers must not change
     * session state that HikariCP cannot reset on return (autoCommit/readOnly/isolation/networkTimeout
     * are reset automatically; things like Oracle ALTER SESSION are not — use open()/openForBulk there).
     * Falls back to an unpooled connection for unsaved definitions and embedded H2 URLs (an idle pooled
     * connection would keep the H2 file locked, blocking VDB delete).
     */
    public Connection openPooled(DataSourceEntity ds) {
        if (!poolable(ds)) return open(ds, READ_TIMEOUT_SECONDS);
        try {
            Connection c = poolFor(ds).getConnection();
            try { c.setNetworkTimeout(Runnable::run, READ_TIMEOUT_SECONDS * 1000); } catch (Exception ignored) {}
            return c;
        } catch (Exception e) {
            throw ApiException.bad("Cannot connect to '" + ds.getName() + "': " + rootMessage(e));
        }
    }

    /** Long-running connection for bulk loads — no (or large) socket read timeout so big loads aren't killed mid-flight. */
    public Connection openForBulk(DataSourceEntity ds) {
        return open(ds, LOAD_READ_TIMEOUT_SECONDS);
    }

    public Connection open(DataSourceEntity ds, int readTimeoutSeconds) {
        int readTimeout = Math.max(0, readTimeoutSeconds);   // 0 = infinite (no socket read timeout)
        try {
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            Properties p = new Properties();
            if (ds.getUsername() != null) p.setProperty("user", ds.getUsername());
            if (ds.getPassword() != null) p.setProperty("password", ds.getPassword());
            if (isPostgresUrl(ds.getJdbcUrl())) {
                p.setProperty("reWriteBatchedInserts", "true");
                p.setProperty("connectTimeout", String.valueOf(LOGIN_TIMEOUT_SECONDS));
                p.setProperty("socketTimeout", String.valueOf(readTimeout));   // 0 = infinite
                p.setProperty("ApplicationName", "ForgeTDM");
            }
            Connection c = DriverManager.getConnection(ds.getJdbcUrl(), p);
            try { c.setNetworkTimeout(Runnable::run, readTimeout * 1000); } catch (Exception ignored) {}
            return c;
        } catch (Exception e) {
            throw ApiException.bad("Cannot connect to '" + ds.getName() + "': " + e.getMessage());
        }
    }

    /** Drop the cached pool for a data source — call when its config changes or it is deleted. */
    public void evict(Long dataSourceId) {
        if (dataSourceId == null) return;
        Pool p = pools.remove(dataSourceId);
        if (p != null) p.ds().close();
    }

    @Override
    public void destroy() {
        pools.values().forEach(p -> p.ds().close());
        pools.clear();
    }

    private boolean poolable(DataSourceEntity ds) {
        String url = ds.getJdbcUrl() == null ? "" : ds.getJdbcUrl().toLowerCase(Locale.ROOT);
        return ds.getId() != null && !url.startsWith("jdbc:h2:");
    }

    private HikariDataSource poolFor(DataSourceEntity ds) {
        String signature = ds.getJdbcUrl() + "|" + ds.getUsername() + "|" + ds.getPassword();
        return pools.compute(ds.getId(), (id, current) -> {
            if (current != null && current.signature().equals(signature)) return current;
            if (current != null) current.ds().close();   // URL or credentials changed — rebuild
            return new Pool(signature, newPool(ds));
        }).ds();
    }

    private HikariDataSource newPool(DataSourceEntity ds) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ds.getJdbcUrl());
        if (ds.getUsername() != null) cfg.setUsername(ds.getUsername());
        if (ds.getPassword() != null) cfg.setPassword(ds.getPassword());
        cfg.setMaximumPoolSize(POOL_MAX_CONNECTIONS);
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(POOL_IDLE_TIMEOUT_SECONDS * 1000L);
        cfg.setMaxLifetime(10 * 60_000L);
        cfg.setConnectionTimeout(LOGIN_TIMEOUT_SECONDS * 1000L);
        cfg.setInitializationFailTimeout(-1);   // lazy: a dead source fails on borrow, not at pool creation
        cfg.setPoolName("forgetdm-ds-" + ds.getId());
        if (isPostgresUrl(ds.getJdbcUrl())) {
            cfg.addDataSourceProperty("reWriteBatchedInserts", "true");
            cfg.addDataSourceProperty("connectTimeout", String.valueOf(LOGIN_TIMEOUT_SECONDS));
            cfg.addDataSourceProperty("socketTimeout", String.valueOf(READ_TIMEOUT_SECONDS));
            cfg.addDataSourceProperty("ApplicationName", "ForgeTDM");
        }
        return new HikariDataSource(cfg);
    }

    private static boolean isPostgresUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:");
    }

    /** Hikari wraps connect failures in pool-timeout exceptions; surface the driver's message instead. */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? String.valueOf(t.getMessage()) : cur.getMessage();
    }

    private static int intEnv(String name, int fallback) {
        try {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : Math.max(1, Integer.parseInt(value.trim()));
        } catch (Exception e) {
            return fallback;
        }
    }
}
