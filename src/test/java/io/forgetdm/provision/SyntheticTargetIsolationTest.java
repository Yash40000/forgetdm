package io.forgetdm.provision;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SyntheticTargetIsolationTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private SyntheticGenService service;

    @BeforeEach
    void setUp() {
        DataSourceRepository repo = mock(DataSourceRepository.class);
        AuditService audit = mock(AuditService.class);
        OwnershipGuard ownership = new OwnershipGuard(audit);
        DataSourceService sources = new DataSourceService(repo, mock(ConnectionFactory.class), audit,
                ownership, jdbc);
        when(repo.findById(2L)).thenReturn(Optional.of(target(2L, 21L, 202L)));
        service = new SyntheticGenService(sources, mock(ConnectionFactory.class), jdbc, audit, 1, 30);
    }

    @Test
    void directGenerationRejectsHiddenTargetBeforeQueueOrLockPersistence() {
        ApiException denial = asAlphaFailure(() -> service.startGenerate(plan(2L)));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verifyNoInteractions(jdbc);
    }

    @Test
    void savedPlanRejectsHiddenTargetBeforeArtifactInsert() {
        SyntheticGenService.SavedSyntheticJobRequest request =
                new SyntheticGenService.SavedSyntheticJobRequest("alpha-job", null, plan(2L));

        ApiException denial = asAlphaFailure(() -> service.saveSyntheticJob(request));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verifyNoInteractions(jdbc);
    }

    @Test
    void planSummaryCannotProbeAnotherGroupsTarget() {
        ApiException denial = asAlphaFailure(() -> service.planSummary(plan(2L)));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verifyNoInteractions(jdbc);
    }

    private ApiException asAlphaFailure(Runnable action) {
        AccessPrincipal alpha = new AccessPrincipal(11L, "alpha-user", "Alpha", Set.of(), Set.of(),
                List.of(new AccessControlService.GroupLite(101L, "alpha")));
        return assertThrows(ApiException.class, () -> AccessContext.callAs(alpha, null, () -> {
            action.run();
            return null;
        }));
    }

    private static SyntheticGenService.GenPlan plan(Long targetId) {
        SyntheticGenService.GenTable table = new SyntheticGenService.GenTable(
                "customers", 1L, List.of(new SyntheticGenService.GenColumn(
                "customer_id", "SEQUENCE", "1", null, true,
                null, null, "BIGINT", null, null)));
        return new SyntheticGenService.GenPlan("alpha-data", List.of(table), 42L, "DB",
                targetId, "public", false, false, "NONE", "INSERT", "NONE", List.of(),
                1000, 1000, false, 0, false);
    }

    private static DataSourceEntity target(Long id, Long ownerId, Long groupId) {
        DataSourceEntity target = new DataSourceEntity();
        target.setId(id);
        target.setName("target-" + id);
        target.setRole("TARGET");
        target.setEnvironment("QA");
        target.setOwnerUserId(ownerId);
        target.setOwnerGroupId(groupId);
        target.setVisibility(OwnershipGuard.GROUP);
        return target;
    }
}
