package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.SqlDialect;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL Server contract simulation. A running SQL Server remains required for live certification;
 * these checks protect the vendor-specific statement, batching, metadata, and native-loader paths.
 */
class SqlServerContractTest {

    @Test
    void keepsSqlServerBatchingBelowBothTsqlLimits() throws Exception {
        Method batch = Class.forName("io.forgetdm.provision.ProvisioningService")
                .getDeclaredMethod("safeJdbcBatchRows", SqlDialect.class, int.class, int.class);
        batch.setAccessible(true);

        assertEquals(1_000, batch.invoke(null, SqlDialect.SQLSERVER, 1, 50_000));
        assertEquals(1_000, batch.invoke(null, SqlDialect.SQLSERVER, 2, 50_000));
        assertEquals(20, batch.invoke(null, SqlDialect.SQLSERVER, 100, 50_000));
        assertEquals(1, batch.invoke(null, SqlDialect.SQLSERVER, 2_100, 50_000));
        assertEquals(2_100, SqlDialect.SQLSERVER.bindParamLimit());
        assertEquals(1_000, SqlDialect.SQLSERVER.maxRowsPerInsert());
    }

    @Test
    void producesRowByRowMergeWithAllValuesBoundOnce() throws Exception {
        Class<?> writer = Class.forName("io.forgetdm.provision.ProvisioningService$TableLoadWriter");
        Method upsert = writer.getDeclaredMethod("upsertSql", SqlDialect.class, String.class, String.class,
                List.class, List.class, List.class);
        upsert.setAccessible(true);

        String sql = (String) upsert.invoke(null, SqlDialect.SQLSERVER, "dbo", "customer",
                List.of("customer_id", "display_name", "status"),
                List.of("customer_id"), List.of("display_name", "status"));

        assertTrue(sql.startsWith("MERGE INTO \"dbo\".\"customer\" AS tgt USING (SELECT ? AS \"customer_id\", ? AS \"display_name\", ? AS \"status\") AS src"));
        assertTrue(sql.contains("ON (tgt.\"customer_id\"=src.\"customer_id\")"));
        assertTrue(sql.contains("WHEN MATCHED THEN UPDATE SET tgt.\"display_name\"=src.\"display_name\",tgt.\"status\"=src.\"status\""));
        assertTrue(sql.contains("WHEN NOT MATCHED THEN INSERT (\"customer_id\",\"display_name\",\"status\") VALUES (src.\"customer_id\",src.\"display_name\",src.\"status\")"));
    }

    @Test
    void reportsActualNativeClientAndDoesNotExposeCredentials() throws Exception {
        DataSourceEntity source = source("jdbc:sqlserver://sql.example.test:1444;databaseName=bank;encrypt=true");
        NativeLoadRegistry registry = new NativeLoadRegistry();

        NativeLoadStrategy strategy = registry.strategyFor(source);
        assertEquals("SQLSERVER_BULK_COPY", strategy.strategy());
        assertEquals("JDBC_MULTI_ROW", strategy.fallback());
        assertFalse(strategy.nativeAvailable());

        NativeLoadSupport.JdbcParts parts = NativeLoadSupport.parse(source);
        assertEquals("sql.example.test", parts.host());
        assertEquals(1444, parts.port());
        assertEquals("bank", parts.database());
        assertFalse(NativeLoadSupport.redact(List.of("-P", "secret-value"), source).toString().contains("secret-value"));

        Method loader = Class.forName("io.forgetdm.provision.SyntheticGenService")
                .getDeclaredMethod("nativeBulkLoader", SqlDialect.class);
        loader.setAccessible(true);
        assertEquals("SQL Server bcp", loader.invoke(null, SqlDialect.SQLSERVER));
    }

    @Test
    void usesSqlServerCatalogContractsForChecksAndDiagnostics() throws Exception {
        Method constraintMetadata = Class.forName("io.forgetdm.provision.SyntheticGenService")
                .getDeclaredMethod("constraintMetadataSource", SqlDialect.class);
        constraintMetadata.setAccessible(true);
        assertEquals("sys.check_constraints.definition", constraintMetadata.invoke(null, SqlDialect.SQLSERVER));

        Method connectorMode = Class.forName("io.forgetdm.datasource.ConnectorDiagnosticsService")
                .getDeclaredMethod("connectorMode", DataSourceEntity.class);
        connectorMode.setAccessible(true);
        assertEquals("BUNDLED_JDBC", connectorMode.invoke(null,
                source("jdbc:sqlserver://sql.example.test;databaseName=bank")));

        assertEquals(SqlDialect.SQLSERVER, SqlDialect.fromUrl("jdbc:sqlserver://sql.example.test;databaseName=bank"));
        assertEquals("TRUNCATE TABLE [dbo].[customer]", SqlDialect.SQLSERVER.truncateSql("[dbo].[customer]"));
        assertFalse(SqlDialect.SQLSERVER.supportsMultiTableTruncate());
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuresStreamingUnicodeAndApplicationIdentityForMicrosoftJdbc() throws Exception {
        Method properties = Class.forName("io.forgetdm.datasource.ConnectionFactory")
                .getDeclaredMethod("vendorProperties", DataSourceEntity.class, int.class);
        properties.setAccessible(true);

        Map<String, String> configured = (Map<String, String>) properties.invoke(null,
                source("jdbc:sqlserver://sql.example.test;databaseName=bank"), 30);

        assertEquals("adaptive", configured.get("responseBuffering"));
        assertEquals("true", configured.get("sendStringParametersAsUnicode"));
        assertEquals("ForgeTDM", configured.get("applicationName"));
    }

    private static DataSourceEntity source(String url) {
        DataSourceEntity source = new DataSourceEntity();
        source.setKind("SQLSERVER");
        source.setJdbcUrl(url);
        source.setUsername("forge_user");
        source.setPassword("secret-value");
        source.setRole("TARGET");
        return source;
    }
}
