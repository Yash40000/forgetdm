package io.forgetdm.datasource;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorDiagnosticsServiceTest {

    @Test
    void reportsMessySchemaRisksWithoutChangingTheSchema() throws Exception {
        String url = "jdbc:h2:mem:connector_diag_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA \"Messy Schema\"");
            st.execute("CREATE TABLE \"Messy Schema\".\"Order\" (\"part a\" INT, part_b INT, payload CLOB, " +
                    "PRIMARY KEY (\"part a\", part_b))");
            st.execute("CREATE TABLE \"Messy Schema\".child (id INT PRIMARY KEY, a INT, b INT, " +
                    "CONSTRAINT fk_child_order FOREIGN KEY (a,b) REFERENCES \"Messy Schema\".\"Order\"(\"part a\",part_b))");
            st.execute("CREATE TABLE \"Messy Schema\".no_key (description VARCHAR(40))");
            st.execute("CREATE TABLE \"Messy Schema\".cycle_a (id INT PRIMARY KEY, b_id INT)");
            st.execute("CREATE TABLE \"Messy Schema\".cycle_b (id INT PRIMARY KEY, a_id INT)");
            st.execute("ALTER TABLE \"Messy Schema\".cycle_a ADD CONSTRAINT fk_a_b FOREIGN KEY (b_id) REFERENCES \"Messy Schema\".cycle_b(id)");
            st.execute("ALTER TABLE \"Messy Schema\".cycle_b ADD CONSTRAINT fk_b_a FOREIGN KEY (a_id) REFERENCES \"Messy Schema\".cycle_a(id)");
        }

        DataSourceEntity source = new DataSourceEntity();
        source.setName("messy-h2");
        source.setKind("H2");
        source.setJdbcUrl(url);
        source.setUsername("sa");
        source.setPassword("");
        source.setRole("BOTH");

        ConnectorDiagnosticsService.Report report = new ConnectorDiagnosticsService(new ConnectionFactory())
                .inspect(source, "Messy Schema", 100);
        Map<String, Object> shape = report.schemaShape();

        assertEquals(5, ((Number) shape.get("tablesScanned")).intValue());
        assertTrue(((Number) shape.get("tablesWithoutPrimaryKey")).intValue() >= 1);
        assertTrue(((Number) shape.get("compositePrimaryKeys")).intValue() >= 1);
        assertTrue(((Number) shape.get("compositeForeignKeys")).intValue() >= 1);
        assertTrue(((Number) shape.get("cycleTables")).intValue() >= 2);
        assertTrue(((Number) shape.get("lobColumns")).intValue() >= 1);
        assertTrue(((Number) shape.get("quotedIdentifiers")).intValue() >= 2);
        assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("CYCLIC_RELATIONSHIPS")));
    }
}
