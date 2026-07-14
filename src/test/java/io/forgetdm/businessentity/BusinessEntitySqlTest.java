package io.forgetdm.businessentity;

import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessEntitySqlTest {
    @Test void quotesIdentifiersForEachCrossApplicationDialect() {
        assertEquals("\"be_core\".\"customers\"", BusinessEntitySql.name(source("POSTGRES"), "be_core", "customers"));
        assertEquals("\"BE_CARDS\".\"CARD_CUSTOMERS\"", BusinessEntitySql.name(source("ORACLE"), "be_cards", "card_customers"));
        assertEquals("\"BANK\".\"CUSTOMERS\"", BusinessEntitySql.name(source("DB2UDB"), "bank", "customers"));
        assertEquals("`digital_engagement`.`digital_customers`", BusinessEntitySql.name(source("MYSQL"), "digital_engagement", "digital_customers"));
        assertEquals("[dbo].[customers]", BusinessEntitySql.name(source("SQLSERVER"), "dbo", "customers"));
    }

    @Test void rejectsIdentifiersThatCouldChangeTheStatement() {
        assertThrows(RuntimeException.class, () -> BusinessEntitySql.identifier(source("MYSQL"), "customer_id;drop"));
    }

    private static DataSourceEntity source(String kind) {
        DataSourceEntity source = new DataSourceEntity();
        source.setKind(kind);
        return source;
    }
}
