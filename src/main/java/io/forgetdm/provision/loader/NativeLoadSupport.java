package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class NativeLoadSupport {
    private NativeLoadSupport() {}

    record JdbcParts(String host, int port, String database, Map<String, String> params, String raw) {}

    static boolean truthy(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    static long timeoutSeconds() {
        try {
            String v = System.getenv("FORGETDM_NATIVE_LOAD_TIMEOUT_SECONDS");
            if (v != null && !v.isBlank()) return Math.max(30, Long.parseLong(v.trim()));
        } catch (Exception ignore) { }
        return Duration.ofHours(1).toSeconds();
    }

    static NativeLoadResult skipped(String strategy, DataSourceEntity target, String message, NativeLoadStrategy description) {
        return new NativeLoadResult(strategy, NativeLoadRegistry.engineOf(target), false, false, -1,
                "FALLBACK", message, List.of(), List.of(), List.of(), "", "", Instant.now(), Instant.now(),
                Map.of("fallback", description.fallback(), "configureHint", description.configureHint()));
    }

    static NativeLoadResult run(String strategy, DataSourceEntity target, List<String> command,
                                List<String> redacted, Map<String, String> env, List<Path> supportFiles,
                                Map<String, Object> details) {
        Instant started = Instant.now();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (env != null) pb.environment().putAll(env);
            pb.redirectErrorStream(false);
            process = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread stdout = pump(process.getInputStream(), out);
            Thread stderr = pump(process.getErrorStream(), err);
            boolean finished = process.waitFor(timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new NativeLoadException("Native loader timed out after " + timeoutSeconds() + " seconds");
            }
            stdout.join(5000);
            stderr.join(5000);
            int exit = process.exitValue();
            Instant finishedAt = Instant.now();
            String stdoutText = out.toString(StandardCharsets.UTF_8);
            String stderrText = err.toString(StandardCharsets.UTF_8);
            return new NativeLoadResult(strategy, NativeLoadRegistry.engineOf(target), true, exit == 0, exit,
                    exit == 0 ? "COMPLETED" : "FAILED",
                    exit == 0 ? "Native load completed" : "Native loader exited with code " + exit,
                    command, redacted, supportFiles, stdoutText, stderrText, started, finishedAt,
                    details == null ? Map.of() : details);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            Instant finishedAt = Instant.now();
            return new NativeLoadResult(strategy, NativeLoadRegistry.engineOf(target), true, false, -1,
                    "FAILED", e.getMessage(), command, redacted, supportFiles, "", "", started, finishedAt,
                    details == null ? Map.of() : details);
        }
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try (InputStream input = in) {
                input.transferTo(out);
            } catch (Exception ignore) { }
        }, "forgetdm-native-loader-output");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static Path write(Path dir, String fileName, String text) {
        try {
            Files.createDirectories(dir);
            Path path = dir.resolve(fileName);
            Files.writeString(path, text, StandardCharsets.UTF_8);
            return path;
        } catch (Exception e) {
            throw new NativeLoadException("Could not write native loader file " + fileName + ": " + e.getMessage(), e);
        }
    }

    static Path supportDir(NativeLoadRequest request) {
        Path parent = request.dataFile() == null ? null : request.dataFile().getParent();
        if (parent != null) return parent;
        try { return Files.createTempDirectory("forgetdm-native-load-"); }
        catch (Exception e) { throw new NativeLoadException("Could not create native loader work directory", e); }
    }

    static String qIdent(String value) {
        String v = value == null ? "" : value.trim();
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    static String qualified(String schema, String table) {
        return (schema == null || schema.isBlank() ? "" : qIdent(schema) + ".") + qIdent(table);
    }

    static String csvPath(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "''");
    }

    static List<String> redact(List<String> command, DataSourceEntity target) {
        String password = target == null ? null : target.getPassword();
        if (password == null || password.isBlank()) return command;
        List<String> out = new ArrayList<>();
        for (String part : command) out.add(part == null ? null : part.replace(password, "****"));
        return out;
    }

    static JdbcParts parse(DataSourceEntity ds) {
        String url = ds == null ? "" : Objects.toString(ds.getJdbcUrl(), "");
        String lower = url.toLowerCase(Locale.ROOT);
        try {
            if (lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")) {
                URI u = URI.create(url.substring("jdbc:".length()));
                String db = cleanPath(u.getPath());
                return new JdbcParts(u.getHost(), u.getPort(), db, queryParams(u.getQuery()), url);
            }
            if (lower.startsWith("jdbc:sqlserver://")) {
                String rest = url.substring("jdbc:sqlserver://".length());
                String[] hpAndParams = rest.split(";", 2);
                String[] hp = hpAndParams[0].split(":", 2);
                Map<String, String> params = semicolonParams(hpAndParams.length > 1 ? hpAndParams[1] : "");
                return new JdbcParts(hp[0], hp.length > 1 ? intOr(hp[1], 1433) : 1433,
                        firstNonBlank(params.get("databaseName"), params.get("database")), params, url);
            }
            if (lower.startsWith("jdbc:db2://")) {
                String rest = url.substring("jdbc:db2://".length());
                String[] hpAndDb = rest.split("/", 2);
                String[] hp = hpAndDb[0].split(":", 2);
                String db = hpAndDb.length > 1 ? hpAndDb[1].split(":", 2)[0] : "";
                return new JdbcParts(hp[0], hp.length > 1 ? intOr(hp[1], 50000) : 50000, db, Map.of(), url);
            }
            if (lower.startsWith("jdbc:oracle:thin:@//")) {
                String rest = url.substring("jdbc:oracle:thin:@//".length());
                String[] hpAndSvc = rest.split("/", 2);
                String[] hp = hpAndSvc[0].split(":", 2);
                return new JdbcParts(hp[0], hp.length > 1 ? intOr(hp[1], 1521) : 1521,
                        hpAndSvc.length > 1 ? hpAndSvc[1] : "", Map.of(), url);
            }
            if (lower.startsWith("jdbc:snowflake://")) {
                URI u = URI.create(url.substring("jdbc:".length()));
                return new JdbcParts(u.getHost(), u.getPort(), "", queryParams(u.getQuery()), url);
            }
        } catch (Exception ignore) { }
        return new JdbcParts("", -1, "", Map.of(), url);
    }

    static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return "";
    }

    private static String cleanPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static Map<String, String> queryParams(String query) {
        if (query == null || query.isBlank()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) out.put(kv[0], kv[1]);
        }
        return out;
    }

    private static Map<String, String> semicolonParams(String text) {
        if (text == null || text.isBlank()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : text.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) out.put(kv[0], kv[1]);
        }
        return out;
    }

    private static int intOr(String v, int def) {
        try { return v == null || v.isBlank() ? def : Integer.parseInt(v.trim()); }
        catch (Exception e) { return def; }
    }
}
