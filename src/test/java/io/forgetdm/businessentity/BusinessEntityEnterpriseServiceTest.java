package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetDefinitionRepository;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.provision.ProvisionJobEntity;
import io.forgetdm.provision.ProvisioningService;
import io.forgetdm.provision.SyntheticGenService;
import io.forgetdm.provision.loader.NativeLoadRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.List;
import java.util.Map;

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
        service = new BusinessEntityEnterpriseService(jdbc, entities, dataSources, audit, provisioning, datasets, synthetic, loaders);

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

        Map<String, Object> gov = service.createGovernanceRequest(1L,
                new BusinessEntityEnterpriseService.GovernanceRequestRequest("BUSINESS_ENTITY", 1L, "RUN", "checker1", "HIGH", "UAT release"));
        assertEquals("PENDING", gov.get("status"));
        Map<String, Object> approved = service.approveGovernanceRequest(((Number) gov.get("id")).longValue(),
                new BusinessEntityEnterpriseService.DecisionRequest("checker1", "approved", "signed"));
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
                        "PLAN_ONLY", ((Number) issue.get("id")).longValue(), ((Number) lookalike.get("id")).longValue()));
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
    void makerCannotApproveOwnRequest() {
        Map<String, Object> gov = service.createGovernanceRequest(1L,
                new BusinessEntityEnterpriseService.GovernanceRequestRequest("BUSINESS_ENTITY", 1L, "RELEASE", "system", "MEDIUM", null));
        var ex = assertThrows(io.forgetdm.common.ApiException.class, () -> service.approveGovernanceRequest(((Number) gov.get("id")).longValue(),
                new BusinessEntityEnterpriseService.DecisionRequest("system", "self approval", "signed")));
        assertTrue(ex.getMessage().contains("Maker-checker"));
    }

    @Test
    void launchBlocksUnapprovedPlan() {
        Map<String, Object> plan = service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest("Unapproved", "SUBSET_MASK", "PROD", "UAT",
                        "PLAN_ONLY", null, null));
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
                        "APPROVED_RUN_READY", null, null));
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
    void approvedLookalikePlanStartsSyntheticJobAndRecordsRun() {
        approveRun();
        Map<String, Object> lookalike = service.createLookalikeProfile(1L,
                new BusinessEntityEnterpriseService.LookalikeProfileRequest("Lookalike", "customer/account", "NO_RAW_VALUES", 25L));
        when(synthetic.startGenerate(any(SyntheticGenService.GenPlan.class))).thenReturn(Map.of("id", "syn-1", "status", "PENDING"));
        Map<String, Object> plan = service.createExecutionPlan(1L,
                new BusinessEntityEnterpriseService.ExecutionPlanRequest("Approved synthetic", "SYNTHETIC_LOOKALIKE", "DEV", "UAT",
                        "APPROVED_RUN_READY", null, ((Number) lookalike.get("id")).longValue()));

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
        service.approveGovernanceRequest(((Number) gov.get("id")).longValue(),
                new BusinessEntityEnterpriseService.DecisionRequest("checker1", "approved", "signed"));
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
        DataSetDefinitionEntity def = new DataSetDefinitionEntity();
        ReflectionTestUtils.setField(def, "id", id);
        def.setName("Customer Scope");
        def.setDataSourceId(101L);
        def.setTargetDataSourceId(102L);
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
