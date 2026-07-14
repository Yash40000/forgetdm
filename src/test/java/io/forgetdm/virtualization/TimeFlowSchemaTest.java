package io.forgetdm.virtualization;

import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeFlowSchemaTest {

    @Test
    void db2StyleSchemaIsCanonicalizedAndMaterializedOutsidePublic() throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:timeflow-schema;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            c.createStatement().execute("CREATE SCHEMA \"OMD1\"");
            assertEquals("OMD1", DataSourceService.normalizeSchema(c, "omd1"));

            SnapshotManifest.TableManifest table = table("CUSTOMERS", "CUSTOMER_ID");
            SnapshotManifest manifest = new SnapshotManifest(1, "omd1", List.of(table), List.of());

            new TimeFlowEngine(new ChunkStore()).materialize(c, SqlDialect.H2, manifest);

            assertTrue(tableExists(c, "OMD1", "CUSTOMERS"));
            assertFalse(tableExists(c, "public", "CUSTOMERS"));
        }
    }

    @Test
    void materializeCreatesMissingCapturedSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:timeflow-create-schema;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            SnapshotManifest.TableManifest table = table("ACCOUNTS", "ACCOUNT_ID");
            SnapshotManifest manifest = new SnapshotManifest(1, "OMD1", List.of(table), List.of());

            new TimeFlowEngine(new ChunkStore()).materialize(c, SqlDialect.H2, manifest);

            assertTrue(tableExists(c, "OMD1", "ACCOUNTS"));
            assertFalse(tableExists(c, "public", "ACCOUNTS"));
        }
    }

    private static SnapshotManifest.TableManifest table(String table, String id) {
        return new SnapshotManifest.TableManifest(
                table,
                List.of(new SnapshotManifest.ColumnInfo(id, Types.BIGINT, "BIGINT", 19, 0, false)),
                List.of(id), 0, List.of());
    }

    private static boolean tableExists(Connection c, String schema, String table) throws Exception {
        try (ResultSet rs = c.getMetaData().getTables(null, schema, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}
