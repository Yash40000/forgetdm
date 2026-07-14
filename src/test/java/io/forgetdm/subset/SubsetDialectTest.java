package io.forgetdm.subset;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubsetDialectTest {
    @Test
    void quotesPlannerIdentifiersForOracleMysqlAndSqlServer() throws Exception {
        assertEquals("\"BE_CARDS\".\"CARD_CUSTOMERS\"", qualified("Oracle", "be_cards", "card_customers"));
        assertEquals("`digital_engagement`.`digital_customers`", qualified("MySQL", "digital_engagement", "digital_customers"));
        assertEquals("[dbo].[customers]", qualified("Microsoft SQL Server", "dbo", "customers"));
    }

    private String qualified(String product, String schema, String table) throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        return SubsetService.q(connection, schema, table);
    }
}
