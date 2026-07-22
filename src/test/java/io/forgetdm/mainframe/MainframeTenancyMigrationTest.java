package io.forgetdm.mainframe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainframeTenancyMigrationTest {

    @Test
    void v67AddsOwnershipAndSharedLegacyBackfillForEveryTopLevelObject() throws IOException {
        Path migration = Path.of("src/main/resources/db/migration/V67__mainframe_object_tenancy.sql");
        String sql = Files.readString(migration).toUpperCase(Locale.ROOT);

        for (String table : new String[]{"MF_CONNECTIONS", "MF_COPYBOOKS", "MF_JOBS"}) {
            assertTrue(sql.contains("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS OWNER_USER_ID"));
            assertTrue(sql.contains("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS OWNER_USERNAME"));
            assertTrue(sql.contains("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS OWNER_GROUP_ID"));
            assertTrue(sql.contains("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS VISIBILITY"));
            assertTrue(sql.contains("UPDATE " + table));
        }
        assertTrue(sql.contains("SET VISIBILITY = 'SHARED'"));
        assertTrue(sql.contains("IDX_MF_CONNECTIONS_TENANCY"));
        assertTrue(sql.contains("IDX_MF_COPYBOOKS_TENANCY"));
        assertTrue(sql.contains("IDX_MF_JOBS_TENANCY"));
    }

    @Test
    void v67ExecutesAndPreservesLegacyObjectsAsShared() throws Exception {
        String raw = Files.readString(Path.of(
                "src/main/resources/db/migration/V67__mainframe_object_tenancy.sql"));
        String executable = raw.replaceAll("(?m)--.*$", "");

        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:mainframe_tenancy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE forge_users (id BIGINT PRIMARY KEY, username VARCHAR(120))");
            statement.execute("CREATE TABLE forge_groups (id BIGINT PRIMARY KEY, name VARCHAR(120))");
            statement.execute("CREATE TABLE mf_connections (id BIGINT PRIMARY KEY, name VARCHAR(120))");
            statement.execute("CREATE TABLE mf_copybooks (id BIGINT PRIMARY KEY, name VARCHAR(120))");
            statement.execute("CREATE TABLE mf_jobs (id BIGINT PRIMARY KEY, name VARCHAR(160), created_by VARCHAR(160))");
            statement.execute("INSERT INTO forge_users VALUES (7, 'legacy-user')");
            statement.execute("INSERT INTO mf_connections VALUES (1, 'legacy-connection')");
            statement.execute("INSERT INTO mf_copybooks VALUES (2, 'legacy-copybook')");
            statement.execute("INSERT INTO mf_jobs VALUES (3, 'legacy-job', 'legacy-user')");

            for (String sql : executable.split(";")) {
                if (!sql.isBlank()) statement.execute(sql.trim());
            }

            try (ResultSet rows = statement.executeQuery(
                    "SELECT owner_user_id, owner_username, owner_group_id, visibility FROM mf_connections WHERE id=1")) {
                assertTrue(rows.next());
                assertNull(rows.getObject("owner_user_id"));
                assertNull(rows.getString("owner_username"));
                assertNull(rows.getObject("owner_group_id"));
                assertEquals("SHARED", rows.getString("visibility"));
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT owner_user_id, owner_username, owner_group_id, visibility FROM mf_copybooks WHERE id=2")) {
                assertTrue(rows.next());
                assertNull(rows.getObject("owner_user_id"));
                assertNull(rows.getString("owner_username"));
                assertNull(rows.getObject("owner_group_id"));
                assertEquals("SHARED", rows.getString("visibility"));
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT owner_user_id, owner_username, owner_group_id, visibility FROM mf_jobs WHERE id=3")) {
                assertTrue(rows.next());
                assertEquals(7L, rows.getLong("owner_user_id"));
                assertEquals("legacy-user", rows.getString("owner_username"));
                assertNull(rows.getObject("owner_group_id"));
                assertEquals("SHARED", rows.getString("visibility"));
            }
        }
    }

    @Test
    void v67PreservesTheFullLegacyCreatedByColumnWithoutAborting() throws Exception {
        String raw = Files.readString(Path.of(
                "src/main/resources/db/migration/V67__mainframe_object_tenancy.sql"));
        String executable = raw.replaceAll("(?m)--.*$", "");
        String legacyActor = "x".repeat(160);

        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:mainframe_tenancy_long_actor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE forge_users (id BIGINT PRIMARY KEY, username VARCHAR(120))");
            statement.execute("CREATE TABLE forge_groups (id BIGINT PRIMARY KEY, name VARCHAR(120))");
            statement.execute("CREATE TABLE mf_connections (id BIGINT PRIMARY KEY, name VARCHAR(120))");
            statement.execute("CREATE TABLE mf_copybooks (id BIGINT PRIMARY KEY, name VARCHAR(120))");
            statement.execute("CREATE TABLE mf_jobs (id BIGINT PRIMARY KEY, name VARCHAR(160), created_by VARCHAR(160))");
            statement.execute("INSERT INTO mf_jobs VALUES (4, 'long-actor-job', '" + legacyActor + "')");

            for (String sql : executable.split(";")) {
                if (!sql.isBlank()) statement.execute(sql.trim());
            }

            try (ResultSet rows = statement.executeQuery(
                    "SELECT owner_user_id, owner_username, owner_group_id, visibility FROM mf_jobs WHERE id=4")) {
                assertTrue(rows.next());
                assertNull(rows.getObject("owner_user_id"));
                assertEquals(legacyActor, rows.getString("owner_username"));
                assertNull(rows.getObject("owner_group_id"));
                assertEquals("SHARED", rows.getString("visibility"));
            }
        }
    }
}
