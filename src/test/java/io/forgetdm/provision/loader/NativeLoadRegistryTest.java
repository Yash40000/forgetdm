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

    @Test
    void db2ZosNeverAdvertisesTheLuwCommandLineLoader() {
        NativeLoadRegistry registry = new NativeLoadRegistry();
        DataSourceEntity ds = source("DB2ZOS", "jdbc:db2://zos.example.test:446/BANKLOC");

        NativeLoadStrategy strategy = registry.strategyFor(ds);

        assertEquals("DB2_LOAD", strategy.strategy());
        assertEquals("DB2ZOS", strategy.engine());
        assertFalse(strategy.nativeAvailable());
        assertEquals("JDBC_BATCH", strategy.fallback());
        assertEquals("JDBC_FALLBACK", strategy.launchMode());
        assertTrue(strategy.configureHint().contains("z/OS"));
    }

    @Test
    void db2ZosAliasesAlsoStayOnTheJdbcFallback() {
        NativeLoadRegistry registry = new NativeLoadRegistry();

        for (String kind : new String[]{"DB2_ZOS", "DB2-ZOS"}) {
            NativeLoadStrategy strategy = registry.strategyFor(
                    source(kind, "jdbc:db2://zos.example.test:446/BANKLOC"));

            assertEquals("DB2_LOAD", strategy.strategy(), kind);
            assertEquals("DB2ZOS", strategy.engine(), kind);
            assertFalse(strategy.nativeAvailable(), kind);
            assertEquals("JDBC_BATCH", strategy.fallback(), kind);
            assertEquals("JDBC_FALLBACK", strategy.launchMode(), kind);
        }
    }

    @Test
    void db2LuwAliasesUseTheLuwLoadStrategy() {
        NativeLoadRegistry registry = new NativeLoadRegistry();

        for (String kind : new String[]{"DB2UDB", "DB2_UDB", "DB2LUW"}) {
            NativeLoadStrategy strategy = registry.strategyFor(
                    source(kind, "jdbc:db2://db2.example.test:50001/BANK"));

            assertEquals("DB2_LOAD", strategy.strategy(), kind);
            assertEquals("DB2UDB", strategy.engine(), kind);
            assertEquals("JDBC_MULTI_ROW", strategy.fallback(), kind);
        }
    }

    @Test
    void acceptedPostgresAndSqlServerAliasesKeepTheirNativeStrategies() {
        NativeLoadRegistry registry = new NativeLoadRegistry();

        NativeLoadStrategy postgres = registry.strategyFor(
                source("POSTGRESQL", "jdbc:postgresql://localhost:5432/BANK"));
        NativeLoadStrategy sqlServer = registry.strategyFor(
                source("SQL_SERVER", "jdbc:sqlserver://localhost:1433;databaseName=BANK"));

        assertEquals("POSTGRES_COPY", postgres.strategy());
        assertEquals("POSTGRES", postgres.engine());
        assertEquals("SQLSERVER_BULK_COPY", sqlServer.strategy());
        assertEquals("SQLSERVER", sqlServer.engine());
    }

    @Test
    void db2LuwUrlRetainsHostPortAndDatabaseForTheLoadClient() {
        NativeLoadSupport.JdbcParts parts = NativeLoadSupport.parse(
                source("DB2UDB", "jdbc:db2://db2.example.test:50001/BANK:retrieveMessagesFromServerOnGetMessage=true"));

        assertEquals("db2.example.test", parts.host());
        assertEquals(50001, parts.port());
        assertEquals("BANK", parts.database());
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
