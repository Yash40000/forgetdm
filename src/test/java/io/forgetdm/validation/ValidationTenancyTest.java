package io.forgetdm.validation;

import io.forgetdm.ai.AiAssistantService;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.provision.ProvisionJobEntity;
import io.forgetdm.provision.ProvisionJobRepository;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ValidationTenancyTest {

    @Test
    void reportCollectionsAndDiagnosisHonorPrivateGroupSharedAndAdminScopes() {
        Harness h = new Harness();
        ValidationReportEntity privateAlpha = report(1L, 1L, 10L, OwnershipGuard.PRIVATE);
        ValidationReportEntity groupAlpha = report(2L, 2L, 10L, OwnershipGuard.GROUP);
        ValidationReportEntity groupBeta = report(3L, 3L, 20L, OwnershipGuard.GROUP);
        ValidationReportEntity shared = report(4L, 3L, 20L, OwnershipGuard.SHARED);
        when(h.reports.findAll()).thenReturn(List.of(privateAlpha, groupAlpha, groupBeta, shared));
        when(h.reports.findById(1L)).thenReturn(Optional.of(privateAlpha));
        when(h.reports.findById(2L)).thenReturn(Optional.of(groupAlpha));

        assertEquals(Set.of(1L, 2L, 4L), reportIds(AccessContext.callAs(alpha(), null, h.service::list)));
        assertEquals(Set.of(2L, 4L), reportIds(AccessContext.callAs(alphaPeer(), null, h.service::list)));
        assertEquals(Set.of(3L, 4L), reportIds(AccessContext.callAs(betaManager(), null, h.service::list)));
        assertEquals(Set.of(1L, 2L, 3L, 4L), reportIds(AccessContext.callAs(admin(), null, h.service::list)));

        AiAssistantService ai = mock(AiAssistantService.class);
        ValidationAdvisor advisor = new ValidationAdvisor(h.service, h.rules, ai, h.audit, h.ownership);
        assertForbidden(() -> advisor.diagnose(1L));
        verifyNoInteractions(ai);

        when(ai.ready()).thenReturn(true);
        when(ai.complete(any(), any(), isNull(), isNull(), eq(true))).thenReturn("{\"remedies\":[]}");
        Map<String, Object> diagnosis = AccessContext.callAs(alphaPeer(), null, () -> advisor.diagnose(2L));
        assertEquals(2L, diagnosis.get("reportId"));
        verify(ai).complete(any(), any(), isNull(), isNull(), eq(true));
    }

    @Test
    void validationAuthorizesLinkedJobAndPolicyThenStampsReportTenant() throws Exception {
        Harness h = new Harness();
        DataSourceEntity target = target(200L);
        when(h.dataSources.get(200L)).thenReturn(target);

        MaskingPolicyEntity policy = policy(70L, 1L, 10L, OwnershipGuard.GROUP);
        when(h.policies.findById(70L)).thenReturn(Optional.of(policy));
        when(h.rules.findByPolicyId(70L)).thenReturn(List.of());

        ProvisionJobEntity job = job(80L, 1L, 10L, OwnershipGuard.GROUP, 200L, 70L);
        when(h.jobs.findById(80L)).thenReturn(Optional.of(job));
        Connection connection = mock(Connection.class);
        when(h.connections.open(target)).thenReturn(connection);
        when(h.reports.save(any())).thenAnswer(invocation -> {
            ValidationReportEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 90L);
            return saved;
        });

        ValidationReportEntity saved = AccessContext.callAs(alpha(), null,
                () -> h.service.validate(null, 200L, null, 80L));

        assertEquals(90L, saved.getId());
        assertEquals(70L, saved.getPolicyId(), "The guarded job policy should become report lineage");
        assertEquals(80L, saved.getJobId());
        assertEquals(1L, saved.getOwnerUserId());
        assertEquals("alpha", saved.getOwnerUsername());
        assertEquals(10L, saved.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, saved.getVisibility());
        assertEquals("PASS", saved.getResult());
        verify(connection).close();
    }

    @Test
    void validationRejectsCrossGroupAndMismatchedPolicyJobReferencesBeforeDatabaseAccess() {
        Harness h = new Harness();
        DataSourceEntity target = target(200L);
        when(h.dataSources.get(200L)).thenReturn(target);

        ProvisionJobEntity betaJob = job(81L, 3L, 20L, OwnershipGuard.GROUP, 200L, null);
        when(h.jobs.findById(81L)).thenReturn(Optional.of(betaJob));
        assertForbiddenAs(alpha(), () -> h.service.validate(null, 200L, null, 81L));

        MaskingPolicyEntity betaPolicy = policy(71L, 3L, 20L, OwnershipGuard.GROUP);
        when(h.policies.findById(71L)).thenReturn(Optional.of(betaPolicy));
        assertForbiddenAs(alpha(), () -> h.service.validate(null, 200L, 71L, null));

        ProvisionJobEntity mismatched = job(82L, 1L, 10L, OwnershipGuard.GROUP, 999L, null);
        when(h.jobs.findById(82L)).thenReturn(Optional.of(mismatched));
        ApiException mismatch = assertThrows(ApiException.class,
                () -> AccessContext.callAs(alpha(), null, () -> h.service.validate(null, 200L, null, 82L)));
        assertEquals(HttpStatus.BAD_REQUEST, mismatch.getStatus());
        assertTrue(mismatch.getMessage().contains("does not match"));

        ProvisionJobEntity policyMismatch = job(83L, 1L, 10L, OwnershipGuard.GROUP, 200L, 70L);
        when(h.jobs.findById(83L)).thenReturn(Optional.of(policyMismatch));
        ApiException mismatchedPolicy = assertThrows(ApiException.class,
                () -> AccessContext.callAs(alpha(), null, () -> h.service.validate(null, 200L, 73L, 83L)));
        assertEquals(HttpStatus.BAD_REQUEST, mismatchedPolicy.getStatus());
        assertTrue(mismatchedPolicy.getMessage().contains("policy does not match"));
        verifyNoInteractions(h.connections);
    }

    @Test
    void applyFixAuthorizesPolicyBeforeReadingOrChangingItsRules() {
        Harness h = new Harness();
        AiAssistantService ai = mock(AiAssistantService.class);
        ValidationAdvisor advisor = new ValidationAdvisor(h.service, h.rules, ai, h.audit, h.ownership);

        MaskingPolicyEntity betaPolicy = policy(72L, 3L, 20L, OwnershipGuard.GROUP);
        when(h.policies.findById(72L)).thenReturn(Optional.of(betaPolicy));
        assertForbiddenAs(alpha(), () -> advisor.applyFix(72L, "customers", "email", "EMAIL", null, null));
        verify(h.rules, never()).findByPolicyId(72L);

        MaskingPolicyEntity alphaPolicy = policy(73L, 1L, 10L, OwnershipGuard.GROUP);
        when(h.policies.findById(73L)).thenReturn(Optional.of(alphaPolicy));
        MaskingRuleEntity rule = new MaskingRuleEntity();
        rule.setPolicyId(73L);
        rule.setTableName("customers");
        rule.setColumnName("email");
        rule.setFunction("REDACT");
        when(h.rules.findByPolicyId(73L)).thenReturn(List.of(rule));
        when(h.rules.save(rule)).thenReturn(rule);

        Map<String, Object> result = AccessContext.callAs(alpha(), null,
                () -> advisor.applyFix(73L, "customers", "email", "EMAIL", null, null));

        assertEquals(true, result.get("ok"));
        assertEquals("EMAIL", rule.getFunction());
        verify(h.rules).save(rule);
    }

    private static final class Harness {
        final ValidationReportRepository reports = mock(ValidationReportRepository.class);
        final MaskingRuleRepository rules = mock(MaskingRuleRepository.class);
        final MaskingPolicyRepository policies = mock(MaskingPolicyRepository.class);
        final ProvisionJobRepository jobs = mock(ProvisionJobRepository.class);
        final DataSourceService dataSources = mock(DataSourceService.class);
        final ConnectionFactory connections = mock(ConnectionFactory.class);
        final AuditService audit = mock(AuditService.class);
        final OwnershipGuard ownership = new OwnershipGuard(audit);
        final ValidationService service = new ValidationService(reports, rules, policies, jobs,
                dataSources, connections, audit, ownership);
    }

    private static ValidationReportEntity report(Long id, Long owner, Long group, String visibility) {
        ValidationReportEntity report = new ValidationReportEntity();
        ReflectionTestUtils.setField(report, "id", id);
        report.setOwnerUserId(owner);
        report.setOwnerUsername("user-" + owner);
        report.setOwnerGroupId(group);
        report.setVisibility(visibility);
        report.setResult("PASS");
        report.setFindingsJson("[]");
        return report;
    }

    private static MaskingPolicyEntity policy(Long id, Long owner, Long group, String visibility) {
        MaskingPolicyEntity policy = new MaskingPolicyEntity();
        policy.setId(id);
        policy.setName("policy-" + id);
        policy.setOwnerUserId(owner);
        policy.setOwnerUsername("user-" + owner);
        policy.setOwnerGroupId(group);
        policy.setVisibility(visibility);
        return policy;
    }

    private static ProvisionJobEntity job(Long id, Long owner, Long group, String visibility,
                                          Long targetId, Long policyId) {
        ProvisionJobEntity job = new ProvisionJobEntity();
        ReflectionTestUtils.setField(job, "id", id);
        job.setOwnerUserId(owner);
        job.setOwnerUsername("user-" + owner);
        job.setOwnerGroupId(group);
        job.setVisibility(visibility);
        job.setTargetId(targetId);
        job.setPolicyId(policyId);
        return job;
    }

    private static DataSourceEntity target(Long id) {
        DataSourceEntity target = new DataSourceEntity();
        target.setId(id);
        target.setName("target-" + id);
        target.setRole("TARGET");
        return target;
    }

    private static Set<Long> reportIds(List<ValidationReportEntity> reports) {
        return reports.stream().map(ValidationReportEntity::getId).collect(java.util.stream.Collectors.toSet());
    }

    private static AccessPrincipal alpha() {
        return principal(1L, "alpha", 10L, Set.of("validation.read", "validation.run", "policy.manage"));
    }

    private static AccessPrincipal alphaPeer() {
        return principal(4L, "alpha-peer", 10L, Set.of("validation.read"));
    }

    private static AccessPrincipal betaManager() {
        return principal(3L, "beta-manager", 20L, Set.of("validation.read", "validation.run", "policy.manage"));
    }

    private static AccessPrincipal admin() {
        return principal(99L, "admin", 99L, Set.of("admin.all"));
    }

    private static AccessPrincipal principal(Long id, String username, Long group, Set<String> permissions) {
        return new AccessPrincipal(id, username, username, Set.of(), permissions,
                List.of(new AccessControlService.GroupLite(group, "group-" + group)));
    }

    private static void assertForbidden(Runnable action) {
        assertForbiddenAs(betaManager(), action);
    }

    private static void assertForbiddenAs(AccessPrincipal principal, Runnable action) {
        ApiException error = assertThrows(ApiException.class,
                () -> AccessContext.callAs(principal, null, () -> { action.run(); return null; }));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }
}
