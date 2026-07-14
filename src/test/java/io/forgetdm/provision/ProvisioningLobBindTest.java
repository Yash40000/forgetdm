package io.forgetdm.provision;

import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.InputStream;
import java.io.Reader;
import java.sql.PreparedStatement;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
