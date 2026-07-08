package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeLoadRegistryTest {
    @Test
    void oracleExecutorFallsBackWhenNativeClientIsNotConfigured() {
        NativeLoadRegistry registry = new NativeLoadRegistry();
        DataSourceEntity ds = source("ORACLE", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1");

        NativeLoadStrategy strategy = registry.strategyFor(ds);
        assertEquals("ORACLE_SQLLOADER_DIRECT_PATH", strategy.strategy());

        NativeLoadResult result = registry.execute(new NativeLoadRequest(ds, "APP", "CUSTOMERS",
                List.of("ID", "NAME"), Path.of("target", "missing.tsv"), "\t", false, "INSERT", Map.of()));
        assertFalse(result.nativeUsed());
        assertEquals("FALLBACK", result.status());
        assertTrue(result.message().contains("not configured"));
    }

    @Test
    void mysqlStrategyReportsConfiguredExecutorNameAndFallback() {
        NativeLoadRegistry registry = new NativeLoadRegistry();
        DataSourceEntity ds = source("MYSQL", "jdbc:mysql://localhost:3306/forgetdm");
        NativeLoadStrategy strategy = registry.strategyFor(ds);
        assertEquals("MYSQL_LOAD_DATA", strategy.strategy());
        assertEquals("ExternalNativeLoadExecutor", strategy.executor());
        assertEquals("JDBC_MULTI_ROW", strategy.fallback());
    }

    private DataSourceEntity source(String kind, String url) {
        DataSourceEntity ds = new DataSourceEntity();
        ds.setKind(kind);
        ds.setJdbcUrl(url);
        ds.setUsername("forgetdm");
        ds.setPassword("secret");
        ds.setRole("TARGET");
        return ds;
    }
}
