package io.forgetdm.datasource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDialectTest {

    @Test
    void oracleInListsRespectTheSeparateExpressionLimit() {
        assertEquals(1_000, SqlDialect.ORACLE.maxInListExpressions());
        assertTrue(SqlDialect.ORACLE.bindParamLimit() > SqlDialect.ORACLE.maxInListExpressions());
    }

    @Test
    void sqlServerRetainsItsStatementParameterLimit() {
        assertEquals(2_100, SqlDialect.SQLSERVER.maxInListExpressions());
    }

    @Test
    void db2AliasesAndSafetyRulesResolveToTheDb2Dialect() {
        for (String kind : new String[]{"DB2", "DB2UDB", "DB2_UDB", "DB2LUW", "DB2ZOS"}) {
            DataSourceEntity source = new DataSourceEntity();
            source.setKind(kind);
            source.setJdbcUrl("jdbc:db2://db2.example.test:50000/BANK");
            assertEquals(SqlDialect.DB2, SqlDialect.of(source), kind);
        }

        assertEquals(SqlDialect.DB2, SqlDialect.fromUrl("jdbc:db2://db2.example.test:50000/BANK"));
        assertEquals("TRUNCATE TABLE \"BANK\".\"CUSTOMERS\" IMMEDIATE",
                SqlDialect.DB2.truncateSql("\"BANK\".\"CUSTOMERS\""));
        assertTrue(SqlDialect.DB2.ddlAutoCommits());
        assertTrue(SqlDialect.isSystemSchema("SYSCAT"));
        assertEquals(32_767, SqlDialect.DB2.bindParamLimit());
    }
}
