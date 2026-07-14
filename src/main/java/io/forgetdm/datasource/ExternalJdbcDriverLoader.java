package io.forgetdm.datasource;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/**
 * Loads administrator-supplied JDBC drivers without rebuilding ForgeTDM. This is the supported path for
 * licensed/proprietary drivers such as Teradata, Sybase, SAP HANA, Snowflake, BigQuery, and Redshift.
 * Only local jars from FORGETDM_JDBC_DRIVER_DIR are loaded; no driver is downloaded at runtime.
 */
@Component
public class ExternalJdbcDriverLoader {
    private final List<URLClassLoader> classLoaders = new ArrayList<>();
    private final List<DriverStatus> statuses = new ArrayList<>();

    @PostConstruct
    void loadConfiguredDrivers() {
        String configured = System.getenv("FORGETDM_JDBC_DRIVER_DIR");
        Path directory = Path.of(configured == null || configured.isBlank() ? "jdbc-drivers" : configured).toAbsolutePath();
        if (!Files.isDirectory(directory)) return;
        try {
            List<Path> jars;
            try (var stream = Files.list(directory)) {
                jars = stream.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                        .toList();
            }
            if (jars.isEmpty()) return;
            URLClassLoader loader = new URLClassLoader(jars.stream().map(ExternalJdbcDriverLoader::url).toArray(URL[]::new),
                    Thread.currentThread().getContextClassLoader());
            classLoaders.add(loader); // Keep driver classes alive for the process lifetime.
            for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
                if (driver.getClass().getClassLoader() != loader) continue; // Ignore bundled drivers visible via parent.
                DriverManager.registerDriver(new DriverShim(driver));
                statuses.add(new DriverStatus(driver.getClass().getName(), driver.getMajorVersion() + "." + driver.getMinorVersion(),
                        directory.toString(), true, null));
            }
            if (statuses.isEmpty())
                statuses.add(new DriverStatus(null, null, directory.toString(), false,
                        "No java.sql.Driver service provider was found in the configured jars."));
        } catch (Exception e) {
            statuses.add(new DriverStatus(null, null, directory.toString(), false, rootMessage(e)));
        }
    }

    public List<DriverStatus> status() {
        List<DriverStatus> out = new ArrayList<>(statuses);
        try {
            Enumeration<Driver> drivers = DriverManager.getDrivers();
            while (drivers.hasMoreElements()) {
                Driver driver = drivers.nextElement();
                if (out.stream().noneMatch(item -> driver.getClass().getName().equals(item.driverClass())))
                    out.add(new DriverStatus(driver.getClass().getName(), driver.getMajorVersion() + "." + driver.getMinorVersion(),
                            "application classpath", true, null));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static URL url(Path path) {
        try { return path.toUri().toURL(); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid driver jar path " + path, e); }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record DriverStatus(String driverClass, String version, String source, boolean loaded, String error) { }

    /** DriverManager checks the caller classloader; this system-loaded shim delegates to plugin-loaded drivers. */
    private record DriverShim(Driver delegate) implements Driver {
        @Override public Connection connect(String url, Properties info) throws SQLException { return delegate.connect(url, info); }
        @Override public boolean acceptsURL(String url) throws SQLException { return delegate.acceptsURL(url); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException { return delegate.getPropertyInfo(url, info); }
        @Override public int getMajorVersion() { return delegate.getMajorVersion(); }
        @Override public int getMinorVersion() { return delegate.getMinorVersion(); }
        @Override public boolean jdbcCompliant() { return delegate.jdbcCompliant(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    }
}
