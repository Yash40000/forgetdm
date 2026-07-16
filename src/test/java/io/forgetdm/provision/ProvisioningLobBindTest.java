package io.forgetdm.provision;

import io.forgetdm.datasource.SqlDialect;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.InputStream;
import java.io.Reader;
import java.sql.PreparedStatement;
import java.sql.SQLXML;
import java.sql.Timestamp;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProvisioningLobBindTest {

    @Test
    void bindsVendorLobObjectsAsPortableStreams() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);

        ProvisioningService.bindJdbcValue(statement, 1, new SerialBlob(new byte[]{1, 2, 3}), Types.BLOB);
        ProvisioningService.bindJdbcValue(statement, 2, new SerialClob("hello".toCharArray()), Types.CLOB);

        verify(statement).setBinaryStream(eq(1), any(InputStream.class), eq(3L));
        verify(statement).setCharacterStream(eq(2), any(Reader.class), eq(5L));
    }

    @Test
    void recognizesLongAndLobColumnsBeforeBatchBuffering() {
        assertTrue(ProvisioningService.containsLobType(new int[]{Types.INTEGER, Types.NCLOB}));
        assertTrue(ProvisioningService.containsLobType(new int[]{Types.LONGVARBINARY}));
        assertFalse(ProvisioningService.containsLobType(new int[]{Types.INTEGER, Types.VARCHAR}));
    }

    @Test
    void readsCharacterLobsWithoutCallingVendorGetStringAndLeavesBinaryLobsOpaque() throws Exception {
        SerialClob clob = new SerialClob("Guidewire note".toCharArray());
        SerialBlob blob = new SerialBlob(new byte[]{0x01, 0x02, 0x03});
        SQLXML xml = mock(SQLXML.class);
        org.mockito.Mockito.when(xml.getString()).thenReturn("<claim id=\"42\"/>");

        assertEquals("Guidewire note", ProvisioningService.jdbcTextValue(clob));
        assertEquals("<claim id=\"42\"/>", ProvisioningService.jdbcTextValue(xml));
        assertEquals("2026-07-16", ProvisioningService.jdbcTextValue(java.sql.Date.valueOf("2026-07-16")));
        assertEquals(null, ProvisioningService.jdbcTextValue(blob));
        assertEquals(null, ProvisioningService.jdbcTextValue(new byte[]{0x01}));
    }

    @Test
    void oracleDirectPathIsBatchCommittedAndNeverUsedForLobsOrUpdates() {
        assertTrue(ProvisioningService.usesOracleDirectPath(SqlDialect.ORACLE, "INSERT", false));
        assertTrue(ProvisioningService.usesOracleDirectPath(SqlDialect.ORACLE, "REPLACE", false));
        assertFalse(ProvisioningService.usesOracleDirectPath(SqlDialect.ORACLE, "INSERT", true));
        assertFalse(ProvisioningService.usesOracleDirectPath(SqlDialect.ORACLE, "UPDATE", false));
        assertFalse(ProvisioningService.usesOracleDirectPath(SqlDialect.POSTGRES, "INSERT", false));
    }

    @Test
    void coercesOracleShapedTemporalTextWithoutSessionFormatConversion() {
        Object date = ProvisioningService.coerce("2026-07-15 00:00:00.0", Types.DATE);
        Object timestamp = ProvisioningService.coerce("2026-07-15 13:14:15.0", Types.TIMESTAMP);

        assertEquals(java.sql.Date.valueOf("2026-07-15"), date);
        assertInstanceOf(Timestamp.class, timestamp);
        assertEquals(Timestamp.valueOf("2026-07-15 13:14:15.0"), timestamp);
    }

    @Test
    void bindsTemporalStringsUsingJdbcTemporalSetters() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);

        ProvisioningService.bindJdbcValue(statement, 1, "2026-07-15 00:00:00.0", Types.DATE);
        ProvisioningService.bindJdbcValue(statement, 2, "2026-07-15 13:14:15.0", Types.TIMESTAMP);

        verify(statement).setDate(1, java.sql.Date.valueOf("2026-07-15"));
        verify(statement).setTimestamp(2, Timestamp.valueOf("2026-07-15 13:14:15.0"));
    }

    @Test
    void normalizesVendorTemporalObjectsBeforeCrossDatabaseBinding() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        Object vendorTimestamp = new Object() {
            @Override public String toString() { return "2026-07-16 19:12:23.123456"; }
        };

        ProvisioningService.bindJdbcValue(statement, 1, vendorTimestamp, Types.TIMESTAMP);

        verify(statement).setTimestamp(1, Timestamp.valueOf("2026-07-16 19:12:23.123456"));
    }

    @Test
    void boundsGeneratedOracleIdentifiersWithoutLosingRunIdentity() {
        String first = ProvisioningService.boundedIdentifier(
                SqlDialect.ORACLE, "fdm_stg_CLOB_ADDRESS_SOURCE_100");
        String same = ProvisioningService.boundedIdentifier(
                SqlDialect.ORACLE, "fdm_stg_CLOB_ADDRESS_SOURCE_100");
        String nextRun = ProvisioningService.boundedIdentifier(
                SqlDialect.ORACLE, "fdm_stg_CLOB_ADDRESS_SOURCE_101");

        assertEquals(30, first.length());
        assertEquals(first, same);
        assertFalse(first.equals(nextRun));
        assertEquals("fdm_stg_short_7", ProvisioningService.boundedIdentifier(
                SqlDialect.ORACLE, "fdm_stg_short_7"));
    }
}
