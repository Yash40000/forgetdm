package io.forgetdm.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.datasource.SqlDialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CdcIncrementalApplierTimeFlowTest {

    @Test
    void h2ReplayNetsPartialUpdatesAndPreservesUnchangedColumns() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:cdc-applier;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            connection.createStatement().execute(
                    "CREATE TABLE public.customers (id BIGINT PRIMARY KEY, name VARCHAR(80), status VARCHAR(20))");
            connection.createStatement().execute(
                    "INSERT INTO public.customers VALUES (1, 'Alice', 'ACTIVE'), (2, 'Bob', 'ACTIVE')");

            CdcChangeEntity update = change(1, "U", "{\"id\":\"1\"}", "{\"name\":\"Alicia\"}");
            CdcChangeEntity insert = change(2, "I", "{\"id\":\"3\"}",
                    "{\"id\":\"3\",\"name\":\"Cara\",\"status\":\"NEW\"}");
            CdcChangeEntity secondUpdate = change(3, "U", "{\"id\":\"3\"}",
                    "{\"status\":\"ACTIVE\"}");
            CdcChangeEntity delete = change(4, "D", "{\"id\":\"2\"}", "{}");

            CdcIncrementalApplier.ApplyResult result = new CdcIncrementalApplier(new ObjectMapper())
                    .apply(connection, SqlDialect.H2, List.of(update, insert, secondUpdate, delete));

            assertEquals(2, result.upserts());
            assertEquals(1, result.deletes());
            assertEquals(0, result.skippedNoPk());
            try (ResultSet rows = connection.createStatement().executeQuery(
                    "SELECT id, name, status FROM public.customers ORDER BY id")) {
                rows.next();
                assertEquals(1, rows.getLong(1));
                assertEquals("Alicia", rows.getString(2));
                assertEquals("ACTIVE", rows.getString(3));
                rows.next();
                assertEquals(3, rows.getLong(1));
                assertEquals("Cara", rows.getString(2));
                assertEquals("ACTIVE", rows.getString(3));
                assertEquals(false, rows.next());
            }
        }
    }

    private static CdcChangeEntity change(long id, String op, String pk, String values) {
        CdcChangeEntity change = new CdcChangeEntity();
        change.setId(id);
        change.setCaptureId(7L);
        change.setDataSourceId(1L);
        change.setSchemaName("public");
        change.setTableName("customers");
        change.setOp(op);
        change.setPkJson(pk);
        change.setChangeJson(values);
        return change;
    }
}
