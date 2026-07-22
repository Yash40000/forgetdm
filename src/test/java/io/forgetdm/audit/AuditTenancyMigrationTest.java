package io.forgetdm.audit;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class AuditTenancyMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V70__audit_event_tenancy.sql");

    @Test
    void migrationDeclaresTenantScopeAndVersionedHashBackfill() throws Exception {
        String sql = Files.readString(MIGRATION).toUpperCase(Locale.ROOT);
        assertTrue(sql.contains("ALTER TABLE AUDIT_EVENTS ADD COLUMN IF NOT EXISTS OWNER_USER_ID"));
        assertTrue(sql.contains("ALTER TABLE AUDIT_EVENTS ADD COLUMN IF NOT EXISTS OWNER_GROUP_ID"));
        assertTrue(sql.contains("ALTER TABLE AUDIT_EVENTS ADD COLUMN IF NOT EXISTS VISIBILITY"));
        assertTrue(sql.contains("ALTER TABLE AUDIT_EVENTS ADD COLUMN IF NOT EXISTS HASH_VERSION"));
        assertTrue(sql.contains("VISIBILITY = 'SHARED'"));
        assertTrue(sql.contains("HASH_VERSION = 1"));
        assertTrue(sql.contains("IDX_AUDIT_EVENTS_TENANCY"));
    }

    @Test
    void migrationPreservesLegacyRowsAndDefaultsNewRowsToVersionTwo() throws Exception {
        String executable = Files.readString(MIGRATION).replaceAll("(?m)--.*$", "");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:audit_tenancy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE forge_users (id BIGINT PRIMARY KEY, username VARCHAR(120))");
            statement.execute("CREATE TABLE forge_groups (id BIGINT PRIMARY KEY, name VARCHAR(120))");
            statement.execute("CREATE TABLE audit_events (id BIGINT PRIMARY KEY, actor VARCHAR(120))");
            statement.execute("INSERT INTO audit_events VALUES (1, 'legacy-user')");

            for (String sql : executable.split(";")) {
                if (!sql.isBlank()) statement.execute(sql.trim());
            }

            try (ResultSet row = statement.executeQuery(
                    "SELECT owner_user_id, owner_username, owner_group_id, visibility, hash_version " +
                            "FROM audit_events WHERE id=1")) {
                assertTrue(row.next());
                assertNull(row.getObject("owner_user_id"));
                assertEquals("legacy-user", row.getString("owner_username"));
                assertNull(row.getObject("owner_group_id"));
                assertEquals("SHARED", row.getString("visibility"));
                assertEquals(1, row.getInt("hash_version"));
            }

            statement.execute("INSERT INTO audit_events (id, actor) VALUES (2, 'new-user')");
            try (ResultSet row = statement.executeQuery(
                    "SELECT visibility, hash_version FROM audit_events WHERE id=2")) {
                assertTrue(row.next());
                assertEquals("GROUP", row.getString("visibility"));
                assertEquals(2, row.getInt("hash_version"));
            }
        }
    }
}
