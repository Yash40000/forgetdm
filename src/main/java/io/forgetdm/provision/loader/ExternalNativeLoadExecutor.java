package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ExternalNativeLoadExecutor implements NativeLoadExecutor {
    private final String engine;
    private final String strategy;
    private final String envFlag;
    private final String binaryEnv;
    private final String fallback;
    private final String notes;

    public ExternalNativeLoadExecutor(String engine, String strategy, String envFlag,
                                      String binaryEnv, String fallback, String notes) {
        this.engine = engine;
        this.strategy = strategy;
        this.envFlag = envFlag;
        this.binaryEnv = binaryEnv;
        this.fallback = fallback;
        this.notes = notes;
    }

    @Override public String strategy() { return strategy; }

    @Override
    public boolean supports(DataSourceEntity target) {
        return engine.equals(NativeLoadRegistry.engineOf(target));
    }

    @Override
    public NativeLoadStrategy describe(DataSourceEntity target) {
        boolean enabled = NativeLoadSupport.truthy(System.getenv(envFlag));
        String bin = System.getenv(binaryEnv);
        boolean hasBinary = bin != null && !bin.isBlank();
        return new NativeLoadStrategy(
                strategy,
                NativeLoadRegistry.engineOf(target),
                enabled && hasBinary,
                getClass().getSimpleName(),
                fallback,
                "EXTERNAL_NATIVE_CLIENT",
                envFlag + "=true and " + binaryEnv + "=<client path>",
                notes + (enabled && hasBinary ? "" : " Native client not configured; approved runs use " + fallback + "."));
    }

    @Override
    public NativeLoadResult execute(NativeLoadRequest request) {
        DataSourceEntity target = request == null ? null : request.target();
        NativeLoadStrategy description = describe(target);
        if (!description.nativeAvailable()) {
            return NativeLoadSupport.skipped(strategy, target, "Native loader is not configured", description);
        }
        if (request == null || request.dataFile() == null || request.table() == null || request.columns() == null || request.columns().isEmpty()) {
            throw new NativeLoadException("Native load request needs target table, data file, and columns.");
        }
        return switch (strategy) {
            case "ORACLE_SQLLOADER_DIRECT_PATH" -> oracleSqlLoader(request);
            case "SQLSERVER_BULK_COPY" -> sqlServerBcp(request);
            case "DB2_LOAD" -> db2Load(request);
            case "MYSQL_LOAD_DATA" -> mysqlLoadData(request);
            case "SNOWFLAKE_STAGE_COPY" -> snowflakeCopy(request);
            default -> NativeLoadSupport.skipped(strategy, target, "No command builder for " + strategy, description);
        };
    }

    private NativeLoadResult oracleSqlLoader(NativeLoadRequest request) {
        DataSourceEntity target = request.target();
        NativeLoadSupport.JdbcParts parts = NativeLoadSupport.parse(target);
        Path dir = NativeLoadSupport.supportDir(request);
        String connect = NativeLoadSupport.firstNonBlank(System.getenv("FORGETDM_ORACLE_SQLLOADER_CONNECT"),
                parts.host().isBlank() ? "" : "//" + parts.host() + ":" + parts.port() + "/" + parts.database());
        String userid = target.getUsername() + "/" + target.getPassword() + "@" + connect;
        String table = NativeLoadSupport.qualified(request.schema(), request.table());
        String ctl = """
                OPTIONS (DIRECT=TRUE, ERRORS=100000)
                LOAD DATA
                CHARACTERSET AL32UTF8
                INFILE '%s'
                APPEND INTO TABLE %s
                FIELDS TERMINATED BY X'09' OPTIONALLY ENCLOSED BY '"'
                TRAILING NULLCOLS
                (%s)
                """.formatted(NativeLoadSupport.csvPath(request.dataFile()), table,
                String.join(", ", request.columns().stream().map(NativeLoadSupport::qIdent).toList()));
        String par = """
                userid=%s
                control=%s
                log=%s
                bad=%s
                direct=true
                """.formatted(userid, dir.resolve("sqlldr.ctl").toAbsolutePath(),
                dir.resolve("sqlldr.log").toAbsolutePath(), dir.resolve("sqlldr.bad").toAbsolutePath());
        Path ctlFile = NativeLoadSupport.write(dir, "sqlldr.ctl", ctl);
        Path parFile = NativeLoadSupport.write(dir, "sqlldr.par", par);
        List<String> command = List.of(System.getenv(binaryEnv), "parfile=" + parFile.toAbsolutePath());
        return runWithRedaction(request, command, List.of(ctlFile, parFile),
                Map.of("connect", maskConnect(connect), "mode", "DIRECT_PATH"));
    }

    private NativeLoadResult sqlServerBcp(NativeLoadRequest request) {
        DataSourceEntity target = request.target();
        NativeLoadSupport.JdbcParts parts = NativeLoadSupport.parse(target);
        String server = NativeLoadSupport.firstNonBlank(System.getenv("FORGETDM_SQLSERVER_BCP_SERVER"),
                parts.host().isBlank() ? "" : parts.host() + (parts.port() > 0 ? "," + parts.port() : ""));
        String database = NativeLoadSupport.firstNonBlank(System.getenv("FORGETDM_SQLSERVER_BCP_DATABASE"), parts.database());
        String table = (request.schema() == null || request.schema().isBlank() ? "dbo" : request.schema()) + "." + request.table();
        List<String> command = new ArrayList<>(List.of(System.getenv(binaryEnv), table, "in",
                request.dataFile().toAbsolutePath().toString(), "-S", server, "-d", database,
                "-c", "-t", "\\t", "-r", "\\n", "-b", batch(request)));
        if (target.getUsername() == null || target.getUsername().isBlank()) {
            command.add("-T");
        } else {
            command.add("-U"); command.add(target.getUsername());
            command.add("-P"); command.add(target.getPassword() == null ? "" : target.getPassword());
        }
        return runWithRedaction(request, command, List.of(), Map.of("database", database, "server", server));
    }

    private NativeLoadResult db2Load(NativeLoadRequest request) {
        DataSourceEntity target = request.target();
        NativeLoadSupport.JdbcParts parts = NativeLoadSupport.parse(target);
        Path dir = NativeLoadSupport.supportDir(request);
        String database = NativeLoadSupport.firstNonBlank(System.getenv("FORGETDM_DB2_DATABASE"), parts.database());
        String table = NativeLoadSupport.qualified(request.schema(), request.table());
        String script = """
                CONNECT TO %s USER %s USING %s;
                LOAD FROM %s OF DEL MODIFIED BY COLDEL0x09 METHOD P (%s) INSERT INTO %s NONRECOVERABLE;
                CONNECT RESET;
                """.formatted(database, target.getUsername(), target.getPassword(),
                request.dataFile().toAbsolutePath(), String.join(",", request.columns().stream().map(NativeLoadSupport::qIdent).toList()), table);
        Path scriptFile = NativeLoadSupport.write(dir, "db2-load.sql", script);
        List<String> command = List.of(System.getenv(binaryEnv), "-tvf", scriptFile.toAbsolutePath().toString());
        return runWithRedaction(request, command, List.of(scriptFile), Map.of("database", database));
    }

    private NativeLoadResult mysqlLoadData(NativeLoadRequest request) {
        DataSourceEntity target = request.target();
        NativeLoadSupport.JdbcParts parts = NativeLoadSupport.parse(target);
        String database = NativeLoadSupport.firstNonBlank(System.getenv("FORGETDM_MYSQL_DATABASE"), parts.database());
        String table = NativeLoadSupport.qualified(request.schema(), request.table()).replace("\"", "`");
        String sql = "LOAD DATA LOCAL INFILE '" + NativeLoadSupport.csvPath(request.dataFile()) + "' INTO TABLE " + table
                + " CHARACTER SET utf8mb4 FIELDS TERMINATED BY '\\t' OPTIONALLY ENCLOSED BY '\"' "
                + "LINES TERMINATED BY '\\n' (" + String.join(",", request.columns().stream().map(c -> "`" + c.replace("`", "``") + "`").toList()) + ")";
        List<String> command = new ArrayList<>(List.of(System.getenv(binaryEnv),
                "--local-infile=1", "-h", parts.host(), "-P", String.valueOf(parts.port() > 0 ? parts.port() : 3306),
                "-u", target.getUsername(), database, "-e", sql));
        Map<String, String> env = new LinkedHashMap<>();
        if (target.getPassword() != null && !target.getPassword().isBlank()) env.put("MYSQL_PWD", target.getPassword());
        return runWithRedaction(request, command, List.of(), Map.of("database", database), env);
    }

    private NativeLoadResult snowflakeCopy(NativeLoadRequest request) {
        Path dir = NativeLoadSupport.supportDir(request);
        String connection = NativeLoadSupport.firstNonBlank(System.getenv("FORGETDM_SNOWSQL_CONNECTION"), "forgetdm");
        String table = NativeLoadSupport.qualified(request.schema(), request.table());
        String stage = "%\"" + request.table().replace("\"", "\"\"") + "\"";
        String script = """
                PUT file://%s @%s AUTO_COMPRESS=TRUE OVERWRITE=TRUE;
                COPY INTO %s (%s)
                  FROM @%s
                  FILE_FORMAT = (TYPE = CSV FIELD_DELIMITER = '\\t' SKIP_HEADER = 0 FIELD_OPTIONALLY_ENCLOSED_BY = '"' NULL_IF = (''))
                  ON_ERROR = ABORT_STATEMENT;
                """.formatted(request.dataFile().toAbsolutePath().toString().replace("\\", "/"),
                stage, table, String.join(",", request.columns().stream().map(NativeLoadSupport::qIdent).toList()), stage);
        Path scriptFile = NativeLoadSupport.write(dir, "snowflake-copy.sql", script);
        List<String> command = List.of(System.getenv(binaryEnv), "-c", connection, "-f", scriptFile.toAbsolutePath().toString());
        return runWithRedaction(request, command, List.of(scriptFile), Map.of("connection", connection));
    }

    private NativeLoadResult runWithRedaction(NativeLoadRequest request, List<String> command, List<Path> supportFiles,
                                              Map<String, Object> details) {
        return runWithRedaction(request, command, supportFiles, details, Map.of());
    }

    private NativeLoadResult runWithRedaction(NativeLoadRequest request, List<String> command, List<Path> supportFiles,
                                              Map<String, Object> details, Map<String, String> env) {
        List<String> redacted = NativeLoadSupport.redact(command, request.target());
        return NativeLoadSupport.run(strategy, request.target(), command, redacted, env, supportFiles, details);
    }

    private String batch(NativeLoadRequest request) {
        String v = request.options() == null ? null : request.options().get("batchSize");
        return v == null || v.isBlank() ? "10000" : v;
    }

    private String maskConnect(String connect) {
        if (connect == null) return "";
        return connect.replaceAll("(?i)(password|pwd)=([^;]+)", "$1=****");
    }
}
