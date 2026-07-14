package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ApiTokenServiceTest {
    @Test
    void createsAuthenticatesAndRevokesHashedAutomationToken() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:api_token_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE forge_users(id BIGINT PRIMARY KEY,username VARCHAR(120),display_name VARCHAR(160),password_hash VARCHAR(500),active BOOLEAN,created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE forge_groups(id BIGINT PRIMARY KEY,name VARCHAR(120),description VARCHAR(500),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE forge_user_groups(user_id BIGINT,group_id BIGINT)");
        jdbc.execute("CREATE TABLE forge_user_roles(user_id BIGINT,role_name VARCHAR(80))");
        jdbc.execute("CREATE TABLE forge_group_roles(group_id BIGINT,role_name VARCHAR(80))");
        jdbc.execute("CREATE TABLE forge_sessions(token_hash VARCHAR(128),user_id BIGINT,expires_at TIMESTAMP,last_seen_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE forge_api_tokens(id VARCHAR(36),user_id BIGINT,name VARCHAR(160),token_hash VARCHAR(120) UNIQUE,token_prefix VARCHAR(24),created_at TIMESTAMP,expires_at TIMESTAMP,last_used_at TIMESTAMP,revoked_at TIMESTAMP,UNIQUE(user_id,name))");
        jdbc.update("INSERT INTO forge_users VALUES (1,'ci-bot','CI Bot','unused',TRUE,CURRENT_TIMESTAMP,NULL)");
        jdbc.update("INSERT INTO forge_user_roles VALUES (1,'TESTER')");
        AccessControlService access = new AccessControlService(jdbc, mock(AuditService.class), "unused", 12);
        AccessPrincipal principal = new AccessPrincipal(1L, "ci-bot", "CI Bot", Set.of("TESTER"), Set.of("provision.run"));

        AccessControlService.ApiTokenCreated created = AccessContext.callAs(principal, null,
                () -> access.createApiToken(new AccessControlService.ApiTokenRequest("jenkins", Instant.now().plusSeconds(3600))));

        assertTrue(created.token().startsWith("ftdm_"));
        assertEquals("ci-bot", access.principalFromToken(created.token()).orElseThrow().username());
        assertEquals(1, AccessContext.callAs(principal, null, () -> access.apiTokens().size()));
        AccessContext.callAs(principal, null, () -> { access.revokeApiToken(created.id()); return null; });
        assertTrue(access.principalFromToken(created.token()).isEmpty());
    }
}
