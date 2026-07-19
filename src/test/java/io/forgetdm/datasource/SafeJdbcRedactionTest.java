package io.forgetdm.datasource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DEF-0013: JDBC URLs reach the audit trail, which is broadly readable and CSV-exportable, so any
 * credential embedded in the URL would be disclosed in clear. Host/port/database must survive
 * redaction because that is the useful part of the record.
 */
class SafeJdbcRedactionTest {

    @Test
    void stripsUserInfoCredentials() {
        String out = DataSourceService.safeJdbc("jdbc:postgresql://alice:s3cret@db.internal:5432/sales");
        assertFalse(out.contains("s3cret"), "password must not survive: " + out);
        assertFalse(out.contains("alice"), "username must not survive: " + out);
        assertTrue(out.contains("db.internal:5432/sales"), "host/db must be preserved: " + out);
    }

    @Test
    void redactsCredentialQueryParameters() {
        String out = DataSourceService.safeJdbc(
                "jdbc:postgresql://db:5432/sales?user=admin&password=hunter2&ssl=true");
        assertFalse(out.contains("hunter2"), "password must not survive: " + out);
        assertFalse(out.contains("admin"), "username must not survive: " + out);
        assertTrue(out.contains("ssl=true"), "benign parameters must be preserved: " + out);
    }

    @Test
    void leavesCleanUrlsIntact() {
        String url = "jdbc:oracle:thin:@localhost:1521:XE";
        assertEquals(url, DataSourceService.safeJdbc(url));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals("", DataSourceService.safeJdbc(null));
        assertEquals("", DataSourceService.safeJdbc("   "));
    }
}
