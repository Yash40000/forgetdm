package io.forgetdm.businessentity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessEntityTenancyMigrationTest {

    @Test
    void migratesLegacyRowsAsSharedAndKeepsGroupDefaultForNewRowsOnH2PostgresMode() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:be_v69;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        jdbc.execute("CREATE TABLE forge_users (id BIGINT PRIMARY KEY, username VARCHAR(200) NOT NULL)");
        jdbc.execute("CREATE TABLE forge_groups (id BIGINT PRIMARY KEY, name VARCHAR(200) NOT NULL)");
        jdbc.execute("CREATE TABLE business_entities (id BIGINT PRIMARY KEY, name VARCHAR(200), owner_username VARCHAR(200))");
        jdbc.update("INSERT INTO forge_users(id, username) VALUES (101, 'alpha-owner')");
        jdbc.update("INSERT INTO forge_groups(id, name) VALUES (10, 'ALPHA')");
        jdbc.update("INSERT INTO business_entities(id, name, owner_username) VALUES (1, 'Legacy entity', 'ALPHA-OWNER')");

        String migration = new ClassPathResource("db/migration/V69__business_entity_tenancy.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String executable = migration.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
        Arrays.stream(executable.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .forEach(jdbc::execute);

        assertEquals(101L, jdbc.queryForObject(
                "SELECT owner_user_id FROM business_entities WHERE id = 1", Long.class));
        assertEquals("SHARED", jdbc.queryForObject(
                "SELECT visibility FROM business_entities WHERE id = 1", String.class));

        jdbc.update("INSERT INTO business_entities(id, name, owner_username, owner_group_id) VALUES (2, 'New entity', 'alpha-owner', 10)");
        assertEquals("GROUP", jdbc.queryForObject(
                "SELECT visibility FROM business_entities WHERE id = 2", String.class));
    }
}
