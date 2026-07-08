package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class NativeLoadRegistry {
    private final List<NativeLoadExecutor> executors = List.of(
            new PostgresCopyLoadExecutor(),
            new ExternalNativeLoadExecutor("ORACLE", "ORACLE_SQLLOADER_DIRECT_PATH",
                    "FORGETDM_ORACLE_SQLLOADER_ENABLED", "FORGETDM_ORACLE_SQLLOADER_BIN", "JDBC_BATCH",
                    "Oracle SQL*Loader direct path adapter."),
            new ExternalNativeLoadExecutor("SQLSERVER", "SQLSERVER_BULK_COPY",
                    "FORGETDM_SQLSERVER_BULK_COPY_ENABLED", "FORGETDM_SQLSERVER_BULK_COPY_BIN", "JDBC_MULTI_ROW",
                    "SQL Server bulk-copy adapter."),
            new ExternalNativeLoadExecutor("MSSQL", "SQLSERVER_BULK_COPY",
                    "FORGETDM_SQLSERVER_BULK_COPY_ENABLED", "FORGETDM_SQLSERVER_BULK_COPY_BIN", "JDBC_MULTI_ROW",
                    "SQL Server bulk-copy adapter."),
            new ExternalNativeLoadExecutor("DB2", "DB2_LOAD",
                    "FORGETDM_DB2_LOAD_ENABLED", "FORGETDM_DB2_LOAD_BIN", "JDBC_MULTI_ROW",
                    "DB2 LOAD adapter."),
            new ExternalNativeLoadExecutor("DB2UDB", "DB2_LOAD",
                    "FORGETDM_DB2_LOAD_ENABLED", "FORGETDM_DB2_LOAD_BIN", "JDBC_MULTI_ROW",
                    "DB2 LOAD adapter."),
            new ExternalNativeLoadExecutor("DB2ZOS", "DB2_LOAD",
                    "FORGETDM_DB2_LOAD_ENABLED", "FORGETDM_DB2_LOAD_BIN", "JDBC_BATCH",
                    "DB2 z/OS LOAD adapter."),
            new ExternalNativeLoadExecutor("MYSQL", "MYSQL_LOAD_DATA",
                    "FORGETDM_MYSQL_LOAD_DATA_ENABLED", "FORGETDM_MYSQL_LOAD_DATA_BIN", "JDBC_MULTI_ROW",
                    "MySQL LOAD DATA adapter."),
            new ExternalNativeLoadExecutor("MARIADB", "MYSQL_LOAD_DATA",
                    "FORGETDM_MYSQL_LOAD_DATA_ENABLED", "FORGETDM_MYSQL_LOAD_DATA_BIN", "JDBC_MULTI_ROW",
                    "MariaDB LOAD DATA adapter."),
            new ExternalNativeLoadExecutor("SNOWFLAKE", "SNOWFLAKE_STAGE_COPY",
                    "FORGETDM_SNOWFLAKE_COPY_ENABLED", "FORGETDM_SNOWSQL_BIN", "JDBC_BATCH",
                    "Snowflake stage COPY adapter."),
            new JdbcBatchLoadExecutor());

    public NativeLoadStrategy strategyFor(DataSourceEntity target) {
        return executorFor(target).describe(target);
    }

    public NativeLoadExecutor executorFor(DataSourceEntity target) {
        for (NativeLoadExecutor executor : executors) {
            if (executor.supports(target)) return executor;
        }
        return new JdbcBatchLoadExecutor();
    }

    public List<NativeLoadStrategy> strategiesFor(Collection<DataSourceEntity> targets) {
        if (targets == null || targets.isEmpty()) return List.of(strategyFor(null));
        return targets.stream().map(this::strategyFor).toList();
    }

    public Map<String, Object> asMap(NativeLoadStrategy strategy) {
        return Map.of(
                "engine", strategy.engine(),
                "strategy", strategy.strategy(),
                "nativeAvailable", strategy.nativeAvailable(),
                "executor", strategy.executor(),
                "fallback", strategy.fallback(),
                "launchMode", strategy.launchMode(),
                "configureHint", strategy.configureHint(),
                "notes", strategy.notes());
    }

    public NativeLoadResult execute(NativeLoadRequest request) {
        return executorFor(request == null ? null : request.target()).execute(request);
    }

    public List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(statusRow("POSTGRES", "POSTGRES_COPY", "Built in through the PostgreSQL JDBC driver", null, null, "JDBC_MULTI_ROW", true));
        out.add(statusRow("ORACLE", "ORACLE_SQLLOADER_DIRECT_PATH", "SQL*Loader direct path",
                "FORGETDM_ORACLE_SQLLOADER_ENABLED", "FORGETDM_ORACLE_SQLLOADER_BIN", "JDBC_BATCH", false));
        out.add(statusRow("SQLSERVER", "SQLSERVER_BULK_COPY", "SQL Server bcp",
                "FORGETDM_SQLSERVER_BULK_COPY_ENABLED", "FORGETDM_SQLSERVER_BULK_COPY_BIN", "JDBC_MULTI_ROW", false));
        out.add(statusRow("DB2", "DB2_LOAD", "DB2 LOAD",
                "FORGETDM_DB2_LOAD_ENABLED", "FORGETDM_DB2_LOAD_BIN", "JDBC_MULTI_ROW", false));
        out.add(statusRow("SNOWFLAKE", "SNOWFLAKE_STAGE_COPY", "SnowSQL stage COPY",
                "FORGETDM_SNOWFLAKE_COPY_ENABLED", "FORGETDM_SNOWSQL_BIN", "JDBC_BATCH", false));
        out.add(statusRow("MYSQL", "MYSQL_LOAD_DATA", "MySQL LOAD DATA LOCAL",
                "FORGETDM_MYSQL_LOAD_DATA_ENABLED", "FORGETDM_MYSQL_LOAD_DATA_BIN", "JDBC_MULTI_ROW", false));
        return out;
    }

    private Map<String, Object> statusRow(String engine, String strategy, String label,
                                          String enabledEnv, String binaryEnv, String fallback, boolean builtIn) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        String enabledValue = enabledEnv == null ? null : System.getenv(enabledEnv);
        String binary = binaryEnv == null ? null : System.getenv(binaryEnv);
        boolean enabled = builtIn || NativeLoadSupport.truthy(enabledValue);
        boolean binaryConfigured = builtIn || (binary != null && !binary.isBlank());
        boolean binaryExists = builtIn || (binaryConfigured && Files.exists(Path.of(binary)));
        boolean available = builtIn || (enabled && binaryConfigured && binaryExists);
        row.put("engine", engine);
        row.put("strategy", strategy);
        row.put("label", label);
        row.put("builtIn", builtIn);
        row.put("enabledEnv", enabledEnv);
        row.put("binaryEnv", binaryEnv);
        row.put("enabled", enabled);
        row.put("binaryConfigured", binaryConfigured);
        row.put("binaryExists", binaryExists);
        row.put("nativeAvailable", available);
        row.put("fallback", fallback);
        row.put("binaryPath", binaryConfigured && binary != null ? binary : "");
        row.put("status", available ? "READY" : enabled ? "MISSING_BINARY" : "FALLBACK");
        row.put("hint", builtIn ? "No setup needed." : "Set " + enabledEnv + "=true and " + binaryEnv + " to the vendor executable path.");
        return row;
    }

    static String engineOf(DataSourceEntity target) {
        if (target == null || target.getKind() == null || target.getKind().isBlank()) return "GENERIC";
        return target.getKind().trim().toUpperCase(Locale.ROOT);
    }
}
