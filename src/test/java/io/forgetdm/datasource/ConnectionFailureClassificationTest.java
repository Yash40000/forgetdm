package io.forgetdm.datasource;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DSRC-002-04/05: a DNS failure and a connect timeout must not be reported identically.
 *
 * <p>Live measurement before the fix: both returned the driver's string "The connection attempt
 * failed." — at 183 ms and 8179 ms respectively — leaving the operator no way to tell "the hostname
 * is wrong" from "the host is firewalled".
 *
 * <p>The tests exercise both credential-safe host extraction and the vendor-independent classifier
 * directly. Real driver wrapping and TLS endpoint behavior still require live connector fixtures;
 * those limits are recorded in the DSRC-002 evidence report.
 */
class ConnectionFailureClassificationTest {

    @Test
    void extractsHostAndPortFromStandardJdbcUrl() {
        assertEquals("localhost:5433", DataSourceService.hostOf("jdbc:postgresql://localhost:5433/forgetdm"));
        assertEquals("db.internal:1433", DataSourceService.hostOf("jdbc:sqlserver://db.internal:1433;databaseName=sales"));
    }

    @Test
    void extractsHostFromOracleThinUrl() {
        // jdbc:oracle:thin:@host:port:SID has no "//" - the fallback branch must still find the host.
        String host = DataSourceService.hostOf("jdbc:oracle:thin:@dbhost:1521:XE");
        assertTrue(host.contains("dbhost"), "expected the Oracle host, got: " + host);
    }

    @Test
    void stripsCredentialsFromTheHostFragment() {
        // A message built from this must never carry the userinfo (DEF-0013).
        String host = DataSourceService.hostOf("jdbc:postgresql://alice:s3cret@dbhost:5432/sales");
        assertEquals("dbhost:5432", host);
        assertFalse(host.contains("s3cret"), "credential leaked into the host fragment: " + host);
        assertFalse(host.contains("alice"), "username leaked into the host fragment: " + host);
    }

    @Test
    void neverReturnsTheQueryString() {
        String host = DataSourceService.hostOf("jdbc:postgresql://dbhost:5432/sales?password=secret&ssl=true");
        assertFalse(host.contains("secret"), "credential leaked into the host fragment: " + host);
        assertFalse(host.contains("?"), "query string leaked into the host fragment: " + host);
    }

    @Test
    void degradesGracefullyOnNullOrOpaqueUrl() {
        assertEquals("the server", DataSourceService.hostOf(null));
        assertEquals("the server", DataSourceService.hostOf("jdbc:h2:mem:testdb"));
    }

    @Test
    void classifiesSqlStateAuthenticationDatabaseAndPrivilegeFailures() throws Exception {
        assertCategory("[AUTH]", new SQLException("password rejected", "28000"), 125);
        assertCategory("[DATABASE_MISSING]", new SQLException("unknown catalog", "3D000"), 125);
        assertCategory("[PRIVILEGE]", new SQLException("permission denied", "42501"), 125);
    }

    @Test
    void classifiesPoolWrappedAuthenticationFailureWhenSqlStateIsNotExposed() throws Exception {
        RuntimeException pooled = new RuntimeException(
                "Cannot connect to 'DSRC-002': Wrong user name or password [28000-224]");
        assertCategory("[AUTH]", pooled, 125);
    }

    @Test
    void distinguishesDnsTimeoutAndRefusedConnections() throws Exception {
        assertCategory("[DNS]", wrapped("connection attempt failed", new UnknownHostException("db.invalid")), 180);
        assertCategory("[TIMEOUT]", wrapped("connection attempt failed", new SocketTimeoutException("connect timed out")), 250);
        assertCategory("[TIMEOUT]", new SQLException("connection attempt failed", "08001"), 8_100);
        assertCategory("[NETWORK]", wrapped("connection refused", new ConnectException("Connection refused")), 250);
    }

    @Test
    void classifiesPoolWrappedDnsAndRefusedPortFailures() throws Exception {
        DataSourceEntity dns = source();
        dns.setJdbcUrl("jdbc:postgresql://missing-dsrc002.invalid:5432/sales");
        assertCategory("[DNS]", new RuntimeException("Cannot connect: The connection attempt failed"), dns, 180);

        assertCategory("[NETWORK]", new RuntimeException(
                "Cannot connect: Connection to 127.0.0.1:5999 refused"), 250);
    }

    @Test
    void tlsFailureIsClosedAndActionableWithoutAStackTrace() throws Exception {
        String result = classify(wrapped("handshake failed",
                new SSLHandshakeException("PKIX path building failed")), 300);

        assertTrue(result.startsWith("[TLS]"), result);
        assertTrue(result.contains("connection was refused, not downgraded"), result);
        assertFalse(result.contains("org.postgresql"), result);
        assertFalse(result.contains("at io.forgetdm"), result);
    }

    @Test
    void classifiedMessageDoesNotExposeJdbcCredentials() throws Exception {
        DataSourceEntity source = source();
        source.setJdbcUrl("jdbc:postgresql://alice:s3cret@db.internal:5432/sales?password=hidden");
        String result = classify(new SQLException("authentication rejected", "28000"), source, 100);

        assertTrue(result.startsWith("[AUTH]"), result);
        assertFalse(result.contains("s3cret"), result);
        assertFalse(result.contains("hidden"), result);
        assertFalse(result.contains("alice"), result);
    }

    private static SQLException wrapped(String message, Exception cause) {
        return new SQLException(message, "08001", cause);
    }

    private static void assertCategory(String expected, Exception error, long elapsedMs) throws Exception {
        String result = classify(error, elapsedMs);
        assertTrue(result.startsWith(expected), "expected " + expected + " but got: " + result);
    }

    private static void assertCategory(String expected, Exception error, DataSourceEntity source, long elapsedMs) throws Exception {
        String result = classify(error, source, elapsedMs);
        assertTrue(result.startsWith(expected), "expected " + expected + " but got: " + result);
    }

    private static String classify(Exception error, long elapsedMs) throws Exception {
        return classify(error, source(), elapsedMs);
    }

    private static String classify(Exception error, DataSourceEntity source, long elapsedMs) throws Exception {
        DataSourceService service = new DataSourceService(null, null, null, null, null);
        Method method = DataSourceService.class.getDeclaredMethod(
                "classify", Exception.class, DataSourceEntity.class, long.class);
        method.setAccessible(true);
        return (String) method.invoke(service, error, source, elapsedMs);
    }

    private static DataSourceEntity source() {
        DataSourceEntity source = new DataSourceEntity();
        source.setName("DSRC-002 fixture");
        source.setKind("POSTGRES");
        source.setJdbcUrl("jdbc:postgresql://db.internal:5432/sales");
        source.setUsername("test_user");
        source.setPassword("not-for-output");
        return source;
    }
}
