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
}
