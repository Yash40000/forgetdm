package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetDefinitionRepository;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.provision.ProvisionJobEntity;
import io.forgetdm.provision.ProvisioningService;
import io.forgetdm.provision.SyntheticGenService;
import io.forgetdm.provision.loader.NativeLoadRegistry;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.GovernedReferenceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessEntityEnterpriseServiceTest {
    private JdbcTemplate jdbc;
    private BusinessEntityService entities;
    private DataSourceRepository dataSources;
    private AuditService audit;
    private ProvisioningService provisioning;
    private DataSetDefinitionRepository datasets;
    private SyntheticGenService synthetic;
    private NativeLoadRegistry loaders;
    private BusinessEntityCapsuleService capsules;
    private GovernedReferenceGuard references;
    private BusinessEntityEnterpriseService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:be_enterprise_ops;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        createTables();
        entities = mock(BusinessEntityService.class);
        dataSources = mock(DataSourceRepository.class);
        audit = mock(AuditService.class);
        provisioning = mock(ProvisioningService.class);
        datasets = mock(DataSetDefinitionRepository.class);
        synthetic = mock(SyntheticGenService.class);
        loaders = new NativeLoadRegistry();
        capsules = mock(BusinessEntityCapsuleService.class);
        references = mock(GovernedReferenceGuard.class);
        service = new BusinessEntityEnterpriseService(jdbc, entities, dataSources, audit, provisioning, datasets, synthetic, loaders,
                capsules, new io.forgetdm.config.ForgeProps(), references);

        BusinessEntityDefinitionEntity entity = new BusinessEntityDefinitionEntity();
        entity.setId(1L);
        entity.setName("Customer 360");
        entity.setDomain("Retail Banking");
        entity.setOwnerUsername("owner1");
        entity.setRootTable("customers");
        entity.setBusinessKeyColumns("customer_id");
        entity.setPrimaryDatasetId(201L);

        BusinessEntityMemberEntity customer = member(11L, 101L, "customers", "customer_id", "customer");
        BusinessEntityMemberEntity account = member(12L, 101L, "accounts", "account_id", "account");
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(entity, List.of(customer, account), null, Map.of(101L, "pg-source")));
        when(dataSources.findAllById(any())).thenReturn(List.of(source(101L, "POSTGRES")));
        when(dataSources.findById(102L)).thenReturn(Optional.of(source(102L, "POSTGRES")));
        when(datasets.findById(201L)).thenReturn(Optional.of(dataset(201L)));
    }

    @Test
    void createsEnterpriseArtifactsAndRunnerPackage() {
        Map<String, Object> catalog = service.syncCatalog(1L);
        assertEquals(3, ((List<?>) catalog.get("catalogAssets")).size());
        Map<String, Object> repeatedCatalog = service.syncCatalog(1L);
        assertEquals(3, ((List<?>) repeatedCatalog.get("catalogAssets")).size());

        Map<String, Object> gov = service.createGovernanceRequest(1L,
                new BusinessEntityEnterpriseService.GovernanceRequestRequest("BUSINESS_ENTITY", 1L, "RUN", "checker1", "HIGH", "UAT release"));
        assertEquals("PENDING", gov.get("status"));
        Map<String, Object> approved = asUser("checker1", () -> service.approveGovernanceRequest(((Number) gov.get("id")).longValue(),
                new BusinessEntityEnterpriseService.DecisionRequest("checker1", "approved", "signed")));
        assertEquals("APPROVED", approved.get("status"));
        assertNotNull(approved.get("eSignatureHash"));

        Map<String, Object> issue = service.createIssuePackage(1L,
                new BusinessEntityEnterpriseService.IssuePackageRequest("INC-100", "Payment defect", "HIGH", "PROD", "UAT",
                        null, null, "MASKED_SUBSET", "MASK_OR_SYNTHETIC", 24, List.of(Map.of("customer_id", 100))));
        assertEquals("INC-100", issue.get("issueKey"));
        assertTrue(String.valueOf(issue.get("packageManifestJson")).contains("Payment defect"));

        Map<String, Object> lookalike = service.createLookalikeProfile(1L,
                new BusinessEntityEnterpriseService.LookalikeProfileRequest("UAT lookalike", "customer/account shape", "NO_RAW_VALUES", 5000L));
        assertTrue(String.valueOf(lookalike.get("safetyReportJson")).contains("rawValuesStored"));

        Map<String, Object> plan = service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest("Customer release", "SUBSET_MASK", "PROD", "UAT",
                        "PLAN_ONLY", ((Number) issue.get("id")).longValue(), ((Number) lookalike.get("id")).longValue(), null));
        assertEquals("APPROVED", plan.get("status"));
        assertTrue(String.valueOf(plan.get("loaderStrategyJson")).contains("POSTGRES_COPY"));

        Map<String, Object> pkg = service.createOperationalPackage(1L,
                new BusinessEntityEnterpriseService.OperationalPackageRequest("Nightly package", ((Number) plan.get("id")).longValue(),
                        "SCHEDULER_RUNNER", "UAT"));
        assertEquals("READY", pkg.get("status"));
        assertTrue(String.valueOf(pkg.get("shellScript")).contains("FORGETDM_TOKEN"));
        assertEquals(1, ((List<?>) pkg.get("versions")).size());
        Map<String, Object> version = service.createPackageVersion(((Number) pkg.get("id")).longValue(),
                new BusinessEntityEnterpriseService.PackageVersionRequest("STANDARD_7_YEAR", 2555, "promote-ready"));
        assertEquals("IMMUTABLE", version.get("status"));
        Map<String, Object> promotion = service.promotePackageVersion(((Number) pkg.get("id")).longValue(),
                new BusinessEntityEnterpriseService.PromotionRequest(((Number) version.get("id")).longValue(), "DEV", "UAT",
                        "checker1", "promote to UAT"));
        assertEquals("PROMOTED", promotion.get("status"));
        verify(audit, atLeastOnce()).log(any(), any(), any());
    }

    @Test
    void deniesBetaGovernancePlanPackageAndVersionIdsBeforeAnySideEffect() {
        jdbc.update("""
                INSERT INTO be_governance_requests(id, entity_id, object_type, object_id, action, requested_by,
                    reviewer, status, risk_level, risk_json, evidence_json)
                VALUES (701, 2, 'BUSINESS_ENTITY', 2, 'RUN', 'beta-maker', 'beta-checker', 'PENDING',
                    'HIGH', '{}', '{}')
                """);
        jdbc.update("""
                INSERT INTO be_entity_execution_plans(id, entity_id, name, operation_type, mode, status,
                    plan_json, validation_json, loader_strategy_json)
                VALUES (702, 2, 'Beta plan', 'SUBSET_MASK', 'PLAN_ONLY', 'APPROVED', '{}', '{}', '[]')
                """);
        jdbc.update("""
                INSERT INTO be_operational_packages(id, entity_id, execution_plan_id, package_type, name, status,
                    manifest_json, shell_script, health_check_json, promotion_json)
                VALUES (703, 2, 702, 'SCHEDULER_RUNNER', 'Beta package', 'READY', '{}', 'blocked', '{}', '{}')
                """);
        jdbc.update("""
                INSERT INTO be_operational_package_versions(id, package_id, entity_id, version_number, status,
                    artifact_hash, manifest_json, shell_script, health_check_json, promotion_json,
                    immutable_manifest_json, retention_policy)
                VALUES (704, 703, 2, 1, 'IMMUTABLE', 'beta-hash', '{}', 'blocked', '{}', '{}', '{}', 'STANDARD')
                """);
        when(entities.getDetail(2L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA entity"));

        assertThrows(ApiException.class, () -> service.approveGovernanceRequest(701L,
                new BusinessEntityEnterpriseService.DecisionRequest("alpha-checker", "blocked", "blocked")));
        assertThrows(ApiException.class, () -> service.launchExecutionPlan(702L,
                new BusinessEntityEnterpriseService.LaunchRequest(null, null, null, null,
                        "REPLACE", "DELETE", null, null, null, "SINGLE", null, null)));
        assertThrows(ApiException.class, () -> service.getOperationalPackage(703L));
        assertThrows(ApiException.class, () -> service.listPackageVersions(703L));
        assertThrows(ApiException.class, () -> service.createPackageVersion(703L,
                new BusinessEntityEnterpriseService.PackageVersionRequest("STANDARD", 30, "blocked")));
        assertThrows(ApiException.class, () -> service.promotePackageVersion(703L,
                new BusinessEntityEnterpriseService.PromotionRequest(704L, "DEV", "QA", "alpha", "blocked")));

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM be_governance_requests WHERE id = 701", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM be_operational_package_versions WHERE package_id = 703", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM be_operational_package_promotions WHERE package_id = 703", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM be_execution_plan_runs WHERE entity_id = 2", Integer.class));
        verifyNoInteractions(provisioning, synthetic);
    }

    @Test
    void governanceAndPackageAuditsAreStructuredAttributedAndSecretFree() {
        Map<String, Object> release = asUser("maker1", () -> service.createGovernanceRequest(1L,
                new BusinessEntityEnterpriseService.GovernanceRequestRequest(
                        "BUSINESS_ENTITY", 1L, "RELEASE", "checker1", "HIGH", "private maker rationale")));
        long releaseId = ((Number) release.get("id")).longValue();
        asUser("checker1", () -> service.approveGovernanceRequest(releaseId,
                new BusinessEntityEnterpriseService.DecisionRequest(
                        "checker1", "private checker rationale", "private-electronic-signature")));

        Map<String, Object> rejected = asUser("maker1", () -> service.createGovernanceRequest(1L,
                new BusinessEntityEnterpriseService.GovernanceRequestRequest(
                        "BUSINESS_ENTITY", 1L, "RELEASE", "checker1", "MEDIUM", "reject this release")));
        long rejectedId = ((Number) rejected.get("id")).longValue();
        asUser("checker1", () -> service.rejectGovernanceRequest(rejectedId,
                new BusinessEntityEnterpriseService.DecisionRequest(
                        "checker1", "private rejection rationale", "private-rejection-signature")));

        Map<String, Object> plan = asUser("maker1", () -> service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest(
                        "Audited release", "SUBSET_MASK", "DEV", "QA", "PLAN_ONLY", null, null, null)));
        Map<String, Object> pkg = asUser("maker1", () -> service.createOperationalPackage(1L,
                new BusinessEntityEnterpriseService.OperationalPackageRequest(
                        "Audited package", ((Number) plan.get("id")).longValue(), "SCHEDULER_RUNNER", "QA")));
        long packageId = ((Number) pkg.get("id")).longValue();
        Map<String, Object> promoteApproval = asUser("maker1", () -> service.createGovernanceRequest(1L,
                new BusinessEntityEnterpriseService.GovernanceRequestRequest(
                        "OPERATIONAL_PACKAGE", packageId, "PROMOTE", "checker1", "HIGH", "promote to QA")));
        long promoteApprovalId = ((Number) promoteApproval.get("id")).longValue();
        asUser("checker1", () -> service.approveGovernanceRequest(promoteApprovalId,
                new BusinessEntityEnterpriseService.DecisionRequest("checker1", "promotion approved", "promotion-signature")));
        Map<String, Object> version = asUser("maker1", () -> service.createPackageVersion(packageId,
                new BusinessEntityEnterpriseService.PackageVersionRequest(
                        "STANDARD_7_YEAR", 2555, "private version note")));
        var spoofedApprover = assertThrows(io.forgetdm.common.ApiException.class,
                () -> asUser("maker1", () -> service.promotePackageVersion(packageId,
                        new BusinessEntityEnterpriseService.PromotionRequest(
                                ((Number) version.get("id")).longValue(), "DEV", "QA", "imposter",
                                "spoofed approver"))));
        assertTrue(spoofedApprover.getMessage().contains("signer of the approved governance request"));

        Map<String, Object> promotion = asUser("maker1", () -> service.promotePackageVersion(packageId,
                new BusinessEntityEnterpriseService.PromotionRequest(
                        ((Number) version.get("id")).longValue(), "DEV", "QA", "checker1", "private promotion note")));

        assertEquals("PROMOTED", promotion.get("status"));
        assertEquals("checker1", promotion.get("approvedBy"));
        assertEquals(promoteApprovalId, ((Number) promotion.get("approvedRequestId")).longValue());

        verify(audit).record(eq("maker1"), eq("BUSINESS_ENTITY_GOVERNANCE_REQUEST"), eq("GOVERNANCE"),
                eq("business-entity-governance-request"), eq(String.valueOf(releaseId)), contains("RELEASE"),
                eq("SUCCESS"), contains("Created governance request"), argThat(metadata ->
                        metadata.contains("\"riskLevel\":\"HIGH\"")
                                && metadata.contains("\"commentsPresent\":true")
                                && !metadata.contains("private maker rationale")));
        verify(audit).record(eq("checker1"), eq("BUSINESS_ENTITY_GOVERNANCE_APPROVED"), eq("GOVERNANCE"),
                eq("business-entity-governance-request"), eq(String.valueOf(releaseId)), contains("RELEASE"),
                eq("SUCCESS"), contains("Approved governance request"), argThat(metadata ->
                        metadata.contains("\"decision\":\"APPROVED\"")
                                && metadata.contains("\"eSignaturePresent\":true")
                                && !metadata.contains("private checker rationale")
                                && !metadata.contains("private-electronic-signature")));
        verify(audit).record(eq("checker1"), eq("BUSINESS_ENTITY_GOVERNANCE_REJECTED"), eq("GOVERNANCE"),
                eq("business-entity-governance-request"), eq(String.valueOf(rejectedId)), contains("RELEASE"),
                eq("SUCCESS"), contains("Rejected governance request"), argThat(metadata ->
                        metadata.contains("\"decision\":\"REJECTED\"")
                                && !metadata.contains("private rejection rationale")
                                && !metadata.contains("private-rejection-signature")));
        verify(audit, atLeast(2)).record(eq("maker1"), eq("BUSINESS_ENTITY_PACKAGE_VERSION_CREATE"), eq("RELEASE"),
                eq("business-entity-package-version"), anyString(), contains("Audited package"), eq("SUCCESS"),
                contains("Created immutable package version"), argThat(metadata ->
                        metadata.contains("\"artifactHash\"")
                                && metadata.contains("\"retentionPolicy\":\"STANDARD_7_YEAR\"")
                                && !metadata.contains("private version note")));
        verify(audit).record(eq("maker1"), eq("BUSINESS_ENTITY_PACKAGE_PROMOTION"), eq("RELEASE"),
                eq("business-entity-package-promotion"), anyString(), contains("DEV -> QA"), eq("SUCCESS"),
                contains("PROMOTED"), argThat(metadata ->
                        metadata.contains("\"governanceApprovedBy\":\"checker1\"")
                                && metadata.contains("\"approvedRequestId\":" + promoteApprovalId)
                                && !metadata.contains("private promotion note")));
    }

    @Test
    void makerCannotApproveOwnRequest() {
        Map<String, Object> gov = service.createGovernanceRequest(1L,
                new BusinessEntityEnterpriseService.GovernanceRequestRequest("BUSINESS_ENTITY", 1L, "RELEASE", "system", "MEDIUM", null));
        var ex = assertThrows(io.forgetdm.common.ApiException.class, () -> service.approveGovernanceRequest(((Number) gov.get("id")).longValue(),
                new BusinessEntityEnterpriseService.DecisionRequest("system", "self approval", "signed")));
        assertTrue(ex.getMessage().contains("Maker-checker"));
    }

    @Test
    void reviewerCannotBeImpersonatedInDecisionPayload() {
        Map<String, Object> gov = service.createGovernanceRequest(1L,
                new BusinessEntityEnterpriseService.GovernanceRequestRequest("BUSINESS_ENTITY", 1L, "RELEASE", "checker1", "MEDIUM", null));
        var ex = assertThrows(io.forgetdm.common.ApiException.class, () -> asUser("intruder", () ->
                service.approveGovernanceRequest(((Number) gov.get("id")).longValue(),
                        new BusinessEntityEnterpriseService.DecisionRequest("checker1", "spoofed approval", "signed"))));
        assertTrue(ex.getMessage().contains("authenticated user"));
    }

    @Test
    void launchBlocksUnapprovedPlan() {
        Map<String, Object> plan = service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest("Unapproved", "SUBSET_MASK", "PROD", "UAT",
                        "PLAN_ONLY", null, null, null));
        assertEquals("READY_FOR_APPROVAL", plan.get("status"));
        var ex = assertThrows(io.forgetdm.common.ApiException.class, () -> service.launchExecutionPlan(((Number) plan.get("id")).longValue(),
                new BusinessEntityEnterpriseService.LaunchRequest(102L, "public", null, "seed1",
                        "REPLACE", "DELETE", 100, null, 99L, "SINGLE", null, null)));
        assertTrue(ex.getMessage().contains("approved"));
    }

    @Test
    void approvedSubsetPlanSubmitsProvisioningJobAndRecordsRun() {
        approveRun();
        ProvisionJobEntity submitted = new ProvisionJobEntity();
        ReflectionTestUtils.setField(submitted, "id", 700L);
        submitted.setStatus("PENDING");
        submitted.setApprovalStatus("NOT_REQUIRED");
        when(provisioning.submit(any(ProvisionJobEntity.class))).thenReturn(submitted);

        Map<String, Object> plan = service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest("Approved subset", "SUBSET_MASK", "PROD", "UAT",
                        "APPROVED_RUN_READY", null, null, null));
        Map<String, Object> launch = service.launchExecutionPlan(((Number) plan.get("id")).longValue(),
                new BusinessEntityEnterpriseService.LaunchRequest(102L, "uat", null, "seed1",
                        "REPLACE", "DELETE", 100, null, 99L, "SINGLE", null, "status = 'ACTIVE'"));

        assertEquals("DATASCOPE", launch.get("engine"));
        assertEquals(700L, launch.get("runId"));
        verify(provisioning).submit(argThat(job ->
                "SUBSET_MASK".equals(job.getJobType())
                        && Long.valueOf(201L).equals(job.getDatasetId())
                        && Long.valueOf(101L).equals(job.getSourceId())
                        && Long.valueOf(102L).equals(job.getTargetId())
                        && job.getSpecJson().contains("\"executionPlanId\"")
                        && job.getSpecJson().contains("\"POSTGRES_COPY\"")));
        Integer runs = jdbc.queryForObject("SELECT COUNT(*) FROM be_execution_plan_runs WHERE execution_plan_id = ?",
                Integer.class, plan.get("id"));
        assertEquals(1, runs);
    }

    @Test
    void capsuleBackedPlanProvisionsWithoutReadingLiveSources() {
        approveRun();
        when(capsules.planAttachmentEvidence(1L, 55L)).thenReturn(Map.of(
                "capsuleInstanceId", 55L, "capsuleVersion", 5, "capsuleStatus", "ACTIVE"));
        when(capsules.provisionToTarget(eq(55L), any())).thenReturn(Map.of(
                "instanceId", 55L, "version", 5, "fragmentsLoaded", 43, "rowsInserted", 265L));

        Map<String, Object> plan = service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest("Customer Micro-DB release", "SUBSET_MASK",
                        "MICRO_DB", "UAT", "PLAN_ONLY", null, null, 55L));
        Map<String, Object> launch = service.launchExecutionPlan(((Number) plan.get("id")).longValue(),
                new BusinessEntityEnterpriseService.LaunchRequest(102L, "uat", null, null,
                        "REPLACE", "DELETE", null, null, 99L, "SINGLE", null, null));

        assertEquals("MICRO_DB", launch.get("engine"));
        assertEquals(false, launch.get("sourceRead"));
        assertEquals(43, launch.get("fragmentsLoaded"));
        verify(capsules).provisionToTarget(eq(55L), argThat(request ->
                Long.valueOf(102L).equals(request.targetDataSourceId())
                        && "uat".equals(request.targetSchema())
                        && Boolean.TRUE.equals(request.deleteExistingByKey())));
        verifyNoInteractions(provisioning);
        Integer runs = jdbc.queryForObject("SELECT COUNT(*) FROM be_execution_plan_runs WHERE engine = 'MICRO_DB'",
                Integer.class);
        assertEquals(1, runs);
    }

    @Test
    void approvedSubsetPlanFansOutAcrossMemberDataScopeBlueprints() {
        approveRun();
        BusinessEntityDefinitionEntity entity = new BusinessEntityDefinitionEntity();
        entity.setId(1L);
        entity.setName("Customer 360");
        entity.setDomain("Retail Banking");
        entity.setRootTable("customers");
        entity.setBusinessKeyColumns("customer_id");
        entity.setPrimaryDatasetId(201L);

        BusinessEntityMemberEntity customer = member(11L, 101L, "customers", "customer_id", "customer");
        customer.setSystemName("Core Banking");
        customer.setDatasetId(201L);
        BusinessEntityMemberEntity card = member(12L, 103L, "cards", "card_id", "card");
        card.setSystemName("Card Platform");
        card.setDatasetId(202L);
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(entity,
                List.of(customer, card), null, Map.of(101L, "core-db2", 103L, "cards-oracle")));
        when(dataSources.findAllById(any())).thenReturn(List.of(source(101L, "DB2UDB"), source(103L, "ORACLE")));
        when(datasets.findById(202L)).thenReturn(Optional.of(dataset(202L, 103L, 104L, "Cards Scope")));
        when(dataSources.findById(104L)).thenReturn(Optional.of(source(104L, "ORACLE")));

        AtomicLong ids = new AtomicLong(800L);
        when(provisioning.submit(any(ProvisionJobEntity.class))).thenAnswer(inv -> {
            ProvisionJobEntity job = inv.getArgument(0);
            ReflectionTestUtils.setField(job, "id", ids.getAndIncrement());
            job.setStatus("PENDING");
            job.setApprovalStatus("NOT_REQUIRED");
            return job;
        });

        Map<String, Object> plan = service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest("Customer multi-app release",
                        "SUBSET_MASK", "PROD", "UAT", "APPROVED_RUN_READY", null, null, null));
        Map<String, Object> launch = service.launchExecutionPlan(((Number) plan.get("id")).longValue(),
                new BusinessEntityEnterpriseService.LaunchRequest(null, null, null, "seed1",
                        "REPLACE", "DELETE", 100, null, 99L, "SINGLE", null, null));

        assertEquals("DATASCOPE_FANOUT", launch.get("engine"));
        assertEquals(2, ((List<?>) launch.get("runs")).size());
        var captor = org.mockito.ArgumentCaptor.forClass(ProvisionJobEntity.class);
        verify(provisioning, times(2)).submit(captor.capture());
        List<ProvisionJobEntity> jobs = captor.getAllValues();
        assertEquals(List.of(201L, 202L), jobs.stream().map(ProvisionJobEntity::getDatasetId).toList());
        assertEquals(List.of(102L, 104L), jobs.stream().map(ProvisionJobEntity::getTargetId).toList());
        assertTrue(jobs.get(0).getSpecJson().contains("\"fanOutRunId\""));
        assertTrue(jobs.get(1).getSpecJson().contains("\"Card Platform\""));
        Integer runRows = jdbc.queryForObject("SELECT COUNT(*) FROM be_execution_plan_runs WHERE execution_plan_id = ?",
                Integer.class, plan.get("id"));
        assertEquals(3, runRows);
        Integer parentRows = jdbc.queryForObject("SELECT COUNT(*) FROM be_execution_plan_runs WHERE engine = 'DATASCOPE_FANOUT'",
                Integer.class);
        assertEquals(1, parentRows);
    }

    @Test
    void preflightsEveryFanOutSliceBeforeSubmittingTheFirstJob() {
        approveRun();
        BusinessEntityDefinitionEntity entity = new BusinessEntityDefinitionEntity();
        entity.setId(1L);
        entity.setName("Customer 360");
        entity.setPrimaryDatasetId(201L);
        BusinessEntityMemberEntity core = member(11L, 101L, "customers", "customer_id", "customer");
        core.setDatasetId(201L);
        BusinessEntityMemberEntity cards = member(12L, 103L, "cards", "card_id", "card");
        cards.setDatasetId(202L);
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(
                entity, List.of(core, cards), null, Map.of(101L, "core", 103L, "cards")));
        when(dataSources.findAllById(any())).thenReturn(List.of(source(101L, "POSTGRES"), source(103L, "ORACLE")));
        DataSetDefinitionEntity cardDataset = dataset(202L, 103L, 104L, "Cards Scope");
        cardDataset.setPolicyId(999L);
        when(datasets.findById(202L)).thenReturn(Optional.of(cardDataset));
        when(dataSources.findById(104L)).thenReturn(Optional.of(source(104L, "ORACLE")));
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA policy")).when(references).policy(999L);

        Map<String, Object> plan = service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest("Preflight all slices", "SUBSET_MASK",
                        "PROD", "UAT", "APPROVED_RUN_READY", null, null, null));

        assertThrows(ApiException.class, () -> service.launchExecutionPlan(
                ((Number) plan.get("id")).longValue(),
                new BusinessEntityEnterpriseService.LaunchRequest(null, null, null, "seed1",
                        "REPLACE", "DELETE", 100, null, 99L, "SINGLE", null, null)));

        verifyNoInteractions(provisioning);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM be_execution_plan_runs WHERE execution_plan_id = ?",
                Integer.class, plan.get("id")));
    }

    @Test
    void approvedLookalikePlanStartsSyntheticJobAndRecordsRun() {
        approveRun();
        Map<String, Object> lookalike = service.createLookalikeProfile(1L,
                new BusinessEntityEnterpriseService.LookalikeProfileRequest("Lookalike", "customer/account", "NO_RAW_VALUES", 25L));
        when(synthetic.startGenerate(any(SyntheticGenService.GenPlan.class))).thenReturn(Map.of("id", "syn-1", "status", "PENDING"));
        Map<String, Object> plan = service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest("Approved synthetic", "SYNTHETIC_LOOKALIKE", "DEV", "UAT",
                        "APPROVED_RUN_READY", null, ((Number) lookalike.get("id")).longValue(), null));

        Map<String, Object> launch = service.launchExecutionPlan(((Number) plan.get("id")).longValue(),
                new BusinessEntityEnterpriseService.LaunchRequest(102L, "uat", null, null,
                        "REPLACE", "DELETE", null, 50L, 123L, "SINGLE", null, null));

        assertEquals("SYNTHETIC", launch.get("engine"));
        assertEquals("syn-1", launch.get("runId"));
        verify(synthetic).startGenerate(argThat(gen ->
                Long.valueOf(102L).equals(gen.targetDataSourceId())
                        && "uat".equals(gen.targetSchema())
                        && gen.tables().size() == 2
                        && gen.tables().get(0).rowCount().equals(50L)));
        Integer runs = jdbc.queryForObject("SELECT COUNT(*) FROM be_execution_plan_runs WHERE engine = 'SYNTHETIC'",
                Integer.class);
        assertEquals(1, runs);
    }

    private void approveRun() {
        Map<String, Object> gov = service.createGovernanceRequest(1L,
                new BusinessEntityEnterpriseService.GovernanceRequestRequest("BUSINESS_ENTITY", 1L, "RUN", "checker1", "HIGH", null));
        asUser("checker1", () -> service.approveGovernanceRequest(((Number) gov.get("id")).longValue(),
                new BusinessEntityEnterpriseService.DecisionRequest("checker1", "approved", "signed")));
    }

    private <T> T asUser(String username, Supplier<T> work) {
        return AccessContext.callAs(new AccessPrincipal(99L, username, username, Set.of("TDM_ARCHITECT"),
                Set.of("datascope.manage", "provision.approve")), null, work);
    }

    private BusinessEntityMemberEntity member(Long id, Long ds, String table, String keys, String role) {
        BusinessEntityMemberEntity m = new BusinessEntityMemberEntity();
        m.setId(id);
        m.setEntityId(1L);
        m.setDataSourceId(ds);
        m.setTableName(table);
        m.setKeyColumns(keys);
        m.setLogicalRole(role);
        return m;
    }

    private DataSourceEntity source(Long id, String kind) {
        DataSourceEntity ds = new DataSourceEntity();
        ds.setId(id);
        ds.setName("source-" + id);
        ds.setKind(kind);
        ds.setRole("BOTH");
        ds.setJdbcUrl("jdbc:h2:mem:none");
        return ds;
    }

    private DataSetDefinitionEntity dataset(Long id) {
        return dataset(id, 101L, 102L, "Customer Scope");
    }

    private DataSetDefinitionEntity dataset(Long id, Long sourceId, Long targetId, String name) {
        DataSetDefinitionEntity def = new DataSetDefinitionEntity();
        ReflectionTestUtils.setField(def, "id", id);
        def.setName(name);
        def.setDataSourceId(sourceId);
        def.setTargetDataSourceId(targetId);
        def.setPolicyId(301L);
        def.setSchemaName("public");
        def.setTargetSchemaName("uat");
        def.setDriverTable("customers");
        def.setDriverFilter("customer_id > 0");
        return def;
    }

    private void createTables() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE be_issue_packages (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_id BIGINT, issue_key VARCHAR(160), title VARCHAR(300),
                  severity VARCHAR(40), source_environment VARCHAR(120), target_environment VARCHAR(120), snapshot_id BIGINT,
                  reservation_id BIGINT, recreation_mode VARCHAR(60), privacy_action VARCHAR(60), status VARCHAR(40),
                  business_keys_json CLOB, package_manifest_json CLOB, replay_instructions CLOB, approval_status VARCHAR(40) DEFAULT 'NOT_REQUESTED',
                  created_by VARCHAR(200), approved_by VARCHAR(200), expires_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_lookalike_profiles (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_id BIGINT, name VARCHAR(220), objective CLOB,
                  privacy_mode VARCHAR(80), sample_policy VARCHAR(80), row_goal BIGINT, generator_plan_json CLOB,
                  safety_report_json CLOB, status VARCHAR(40), created_by VARCHAR(200), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_catalog_assets (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_id BIGINT, asset_type VARCHAR(80), asset_id BIGINT,
                  qualified_name VARCHAR(500) UNIQUE, display_name VARCHAR(300), owner_username VARCHAR(200), domain VARCHAR(120),
                  tags CLOB, certification_status VARCHAR(60), lineage_json CLOB, dependencies_json CLOB, quality_score DOUBLE,
                  status VARCHAR(40), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_governance_requests (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_id BIGINT, object_type VARCHAR(80), object_id BIGINT,
                  action VARCHAR(120), requested_by VARCHAR(200), reviewer VARCHAR(200), status VARCHAR(40), risk_level VARCHAR(40),
                  risk_json CLOB, evidence_json CLOB, comments CLOB, signed_by VARCHAR(200), signed_at TIMESTAMP,
                  e_signature_hash VARCHAR(200), due_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_entity_execution_plans (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_id BIGINT, name VARCHAR(220), operation_type VARCHAR(80),
                  source_environment VARCHAR(120), target_environment VARCHAR(120), mode VARCHAR(80), status VARCHAR(40),
                  issue_package_id BIGINT, lookalike_profile_id BIGINT, approved_request_id BIGINT, plan_json CLOB,
                  validation_json CLOB, loader_strategy_json CLOB, created_by VARCHAR(200), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_operational_packages (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_id BIGINT, execution_plan_id BIGINT, package_type VARCHAR(80),
                  name VARCHAR(220), status VARCHAR(40), manifest_json CLOB, shell_script CLOB, health_check_json CLOB,
                  promotion_json CLOB, created_by VARCHAR(200), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_operational_package_versions (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, package_id BIGINT, entity_id BIGINT, version_number INTEGER,
                  status VARCHAR(40), artifact_hash VARCHAR(128), manifest_json CLOB, shell_script CLOB, health_check_json CLOB,
                  promotion_json CLOB, immutable_manifest_json CLOB, retention_policy VARCHAR(80), retention_until TIMESTAMP,
                  created_by VARCHAR(200), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_operational_package_promotions (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, package_id BIGINT, entity_id BIGINT, version_id BIGINT,
                  from_environment VARCHAR(120), to_environment VARCHAR(120), status VARCHAR(40), approved_request_id BIGINT,
                  evidence_json CLOB, requested_by VARCHAR(200), approved_by VARCHAR(200), promoted_at TIMESTAMP,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_execution_plan_runs (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, entity_id BIGINT, execution_plan_id BIGINT, engine VARCHAR(40),
                  engine_run_id VARCHAR(120), engine_status VARCHAR(80), status VARCHAR(40), launch_request_json CLOB,
                  launch_result_json CLOB, loader_strategy_json CLOB, created_by VARCHAR(200),
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP)
                """);
    }
}
