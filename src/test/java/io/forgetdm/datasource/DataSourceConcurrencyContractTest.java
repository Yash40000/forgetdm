package io.forgetdm.datasource;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.common.GlobalExceptionHandler;
import io.forgetdm.security.OwnershipGuard;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataSourceConcurrencyContractTest {

    @Test
    void datasourceEntityUsesJpaOptimisticVersioning() throws Exception {
        var field = DataSourceEntity.class.getDeclaredField("version");
        assertTrue(field.isAnnotationPresent(Version.class));
    }

    @Test
    void updateRejectsMissingAndStaleClientVersionsWithoutSaving() {
        Fixture fixture = fixture("BOTH", 4L);

        DataSourceEntity missing = new DataSourceEntity();
        ApiException missingError = assertThrows(ApiException.class,
                () -> fixture.service.update(1L, missing));
        assertEquals(HttpStatus.CONFLICT, missingError.getStatus());
        assertTrue(missingError.getMessage().contains("version is required"));

        DataSourceEntity stale = new DataSourceEntity();
        stale.setVersion(3L);
        ApiException staleError = assertThrows(ApiException.class,
                () -> fixture.service.update(1L, stale));
        assertEquals(HttpStatus.CONFLICT, staleError.getStatus());
        assertTrue(staleError.getMessage().contains("changed after you opened it"));
        verify(fixture.repo, never()).save(any());
    }

    @Test
    void updateWithCurrentVersionPreservesSecretAndSavesMetadata() {
        Fixture fixture = fixture("BOTH", 4L);
        when(fixture.repo.findAll()).thenReturn(List.of(fixture.current));
        when(fixture.repo.save(any(DataSourceEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DataSourceEntity update = new DataSourceEntity();
        update.setVersion(4L);
        update.setTags("certified,qa");
        update.setEnvironment("QA");

        DataSourceEntity saved = fixture.service.update(1L, update);

        assertEquals("certified,qa", saved.getTags());
        assertEquals("QA", saved.getEnvironment());
        assertEquals("stored-secret", saved.getPassword());
        verify(fixture.repo).save(fixture.current);
    }

    @Test
    void sourceTargetAndBothCapabilitiesAreEnforcedBeforeUse() {
        Fixture source = fixture("SOURCE", 0L);
        assertSame(source.current, source.service.getSourceCapable(1L));
        ApiException sourceAsTarget = assertThrows(ApiException.class,
                () -> source.service.getTargetCapable(1L));
        assertTrue(sourceAsTarget.getMessage().contains("not target-capable"));

        Fixture target = fixture("TARGET", 0L);
        assertSame(target.current, target.service.getTargetCapable(1L));
        ApiException targetAsSource = assertThrows(ApiException.class,
                () -> target.service.getSourceCapable(1L));
        assertTrue(targetAsSource.getMessage().contains("not source-capable"));

        Fixture both = fixture("BOTH", 0L);
        assertSame(both.current, both.service.getSourceCapable(1L));
        assertSame(both.current, both.service.getTargetCapable(1L));
    }

    @Test
    void persistenceRaceReturnsSanitizedConflict() {
        AuditService audit = mock(AuditService.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(audit);
        var response = handler.optimisticLock(
                new OptimisticLockingFailureException("SQL update data_sources password=secret"),
                new MockHttpServletRequest("PUT", "/api/datasources/1"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        String error = response.getBody().get("error");
        assertTrue(error.contains("Refresh and retry"));
        assertFalse(error.contains("SQL"));
        assertFalse(error.contains("secret"));
        verify(audit).record(isNull(), eq("VALIDATION_FAILED"), eq("SECURITY"), eq("api-request"),
                isNull(), eq("/api/datasources/1"), eq("FAILURE"), contains("status=409"), isNull());
    }

    private static Fixture fixture(String role, Long version) {
        DataSourceRepository repo = mock(DataSourceRepository.class);
        ConnectionFactory connections = mock(ConnectionFactory.class);
        AuditService audit = mock(AuditService.class);
        OwnershipGuard ownership = mock(OwnershipGuard.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        DataSourceEntity current = new DataSourceEntity();
        current.setId(1L);
        current.setName("core-banking");
        current.setKind("POSTGRES");
        current.setRole(role);
        current.setJdbcUrl("jdbc:postgresql://localhost:5433/source");
        current.setUsername("forge");
        current.setPassword("stored-secret");
        current.setVersion(version);
        when(repo.findById(1L)).thenReturn(Optional.of(current));

        return new Fixture(new DataSourceService(repo, connections, audit, ownership, jdbc), repo, current);
    }

    private record Fixture(DataSourceService service, DataSourceRepository repo, DataSourceEntity current) {}
}
