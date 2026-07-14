package io.forgetdm.businessentity;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class BusinessEntityCapsuleServiceTest {

    @Test
    void normalizesVendorTimestampThroughJdbcTextBoundary() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnType(1)).thenReturn(Types.TIMESTAMP);
        when(rs.getString(1)).thenReturn("2026-07-13 16:42:11.123456");

        Object value = BusinessEntityCapsuleService.readPortableValue(rs, md, 1);

        assertEquals("2026-07-13 16:42:11.123456", value);
        verify(rs, never()).getObject(1);
    }

    @Test
    void encodesBinaryPayloadForPortableEncryptedJson() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnType(1)).thenReturn(Types.BLOB);
        when(rs.getBytes(1)).thenReturn(new byte[]{1, 2, 3, 4});

        assertEquals("AQIDBA==", BusinessEntityCapsuleService.readPortableValue(rs, md, 1));
    }
}
