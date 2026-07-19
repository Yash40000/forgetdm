package io.forgetdm.businessentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import io.forgetdm.provision.loader.NativeLoadStrategy;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.security.AccessContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BusinessEntityEnterpriseService {
    private final JdbcTemplate jdbc;
    private final BusinessEntityService entities;
    private final DataSourceRepository dataSources;
    private final AuditService audit;
    private final ProvisioningService provisioning;
    private final DataSetDefinitionRepository datasets;
    private final SyntheticGenService synthetic;
    private final NativeLoadRegistry loaders;
    private final BusinessEntityCapsuleService capsules;
    private final ForgeProps props;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private record DataScopeFanOutSlice(String sliceKey,
                                        String label,
                                        DataSetDefinitionEntity dataset,
                                        Long targetDataSourceId,
                                        DataSourceEntity target,
                                        NativeLoadStrategy strategy,
                                        List<BusinessEntityMemberEntity> members) {}

    public BusinessEntityEnterpriseService(JdbcTemplate jdbc,
                                           BusinessEntityService entities,
                                           DataSourceRepository dataSources,
                                           AuditService audit,
                                           ProvisioningService provisioning,
                                           DataSetDefinitionRepository datasets,
                                           SyntheticGenService synthetic,
                                           NativeLoadRegistry loaders,
                                           BusinessEntityCapsuleService capsules,
                                           ForgeProps props) {
        this.jdbc = jdbc;
        this.entities = entities;
        this.dataSources = dataSources;
        this.audit = audit;
        this.provisioning = provisioning;
        this.datasets = datasets;
        this.synthetic = synthetic;
        this.loaders = loaders;
        this.capsules = capsules;
        this.props = props;
    }

    public record IssuePackageRequest(String issueKey, String title, String severity, String sourceEnvironment,
                                      String targetEnvironment, Long snapshotId, Long reservationId,
                                      String recreationMode, String privacyAction, Integer ttlHours,
                                      List<Map<String, Object>> businessKeys) {}
    public record LookalikeProfileRequest(String name, String objective, String privacyMode, Long rowGoal) {}
    public record GovernanceRequestRequest(String objectType, Long objectId, String action, String reviewer,
                                           String riskLevel, String comments) {}
    public record DecisionRequest(String reviewer, String comments, String eSignature) {}
    public record ExecutionPlanRequest(String name, String operationType, String sourceEnvironment,
                                       String targetEnvironment, String mode, Long issuePackageId,
                                       Long lookalikeProfileId, Long capsuleInstanceId) {}
    public record OperationalPackageRequest(String name, Long executionPlanId, String packageType,
                                            String targetEnvironment) {}
    public record PackageVersionRequest(String retentionPolicy, Integer retentionDays, String changeNote) {}
    public record PromotionRequest(Long versionId, String fromEnvironment, String toEnvironment,
                                   String approver, String comments) {}
    public record LaunchRequest(Long targetDataSourceId, String targetSchema, Long policyId, String maskingSeed,
                                String loadAction, String targetPrep, Integer maxRows, Long rowCount, Long seed,
                                String executionMode, Long lookalikeProfileId, String filter) {}

    public Map<String, Object> dashboard(Long entityId) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalogAssets", rows("""
                SELECT id, asset_type AS "assetType", asset_id AS "assetId", qualified_name AS "qualifiedName",
                       display_name AS "displayName", owner_username AS "ownerUsername", domain, tags,
                       certification_status AS "certificationStatus", quality_score AS "qualityScore", status,
                       updated_at AS "updatedAt"
                  FROM be_catalog_assets WHERE entity_id = ? ORDER BY asset_type, display_name
                """, entityId));
        out.put("issuePackages", rows("""
                SELECT id, issue_key AS "issueKey", title, severity, source_environment AS "sourceEnvironment",
                       target_environment AS "targetEnvironment", recreation_mode AS "recreationMode",
                       privacy_action AS "privacyAction", status, approval_status AS "approvalStatus",
                       created_by AS "createdBy", created_at AS "createdAt", expires_at AS "expiresAt"
                  FROM be_issue_packages WHERE entity_id = ? ORDER BY created_at DESC
                """, entityId));
        out.put("lookalikeProfiles", rows("""
                SELECT id, name, objective, privacy_mode AS "privacyMode", sample_policy AS "samplePolicy",
                       row_goal AS "rowGoal", status, created_by AS "createdBy", updated_at AS "updatedAt"
                  FROM be_lookalike_profiles WHERE entity_id = ? ORDER BY created_at DESC
                """, entityId));
        out.put("governanceRequests", rows("""
                SELECT id, object_type AS "objectType", object_id AS "objectId", action, requested_by AS "requestedBy",
                       reviewer, status, risk_level AS "riskLevel", signed_by AS "signedBy", signed_at AS "signedAt",
                       created_at AS "createdAt", updated_at AS "updatedAt"
                  FROM be_governance_requests WHERE entity_id = ? ORDER BY created_at DESC
                """, entityId));
        out.put("executionPlans", rows("""
                SELECT id, name, operation_type AS "operationType", source_environment AS "sourceEnvironment",
                       target_environment AS "targetEnvironment", mode, status, approved_request_id AS "approvedRequestId",
                       plan_json AS "planJson", validation_json AS "validationJson",
                       loader_strategy_json AS "loaderStrategyJson", created_by AS "createdBy", updated_at AS "updatedAt"
                  FROM be_entity_execution_plans WHERE entity_id = ? ORDER BY created_at DESC
                """, entityId));
        out.put("operationalPackages", rows("""
                SELECT id, execution_plan_id AS "executionPlanId", package_type AS "packageType", name, status,
                       created_by AS "createdBy", updated_at AS "updatedAt"
                  FROM be_operational_packages WHERE entity_id = ? ORDER BY created_at DESC
                """, entityId));
        out.put("packageVersions", rows("""
                SELECT id, package_id AS "packageId", version_number AS "versionNumber", status,
                       artifact_hash AS "artifactHash", retention_until AS "retentionUntil",
                       created_by AS "createdBy", created_at AS "createdAt"
                  FROM be_operational_package_versions WHERE entity_id = ? ORDER BY created_at DESC
                """, entityId));
        out.put("packagePromotions", rows("""
                SELECT id, package_id AS "packageId", version_id AS "versionId",
                       from_environment AS "fromEnvironment", to_environment AS "toEnvironment", status,
                       approved_request_id AS "approvedRequestId", promoted_at AS "promotedAt",
                       requested_by AS "requestedBy", created_at AS "createdAt"
                  FROM be_operational_package_promotions WHERE entity_id = ? ORDER BY created_at DESC
                """, entityId));
        out.put("executionRuns", rows("""
                SELECT id, execution_plan_id AS "executionPlanId", engine, engine_run_id AS "engineRunId",
                       engine_status AS "engineStatus", status, launch_result_json AS "launchResultJson",
                       loader_strategy_json AS "loaderStrategyJson", created_by AS "createdBy",
                       created_at AS "createdAt", updated_at AS "updatedAt"
                  FROM be_execution_plan_runs WHERE entity_id = ? ORDER BY created_at DESC
                """, entityId));
        out.put("loaderStrategies", loaderStrategies(detail));
        return out;
    }

    public Map<String, Object> createIssuePackage(Long entityId, IssuePackageRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        String issueKey = required(request == null ? null : request.issueKey(), "Issue key");
        String title = required(request == null ? null : request.title(), "Issue title");
        String mode = upperDefault(request == null ? null : request.recreationMode(), "MASKED_SUBSET");
        String privacy = upperDefault(request == null ? null : request.privacyAction(), "MASK_OR_SYNTHETIC");
        List<Map<String, Object>> keys = request == null || request.businessKeys() == null ? List.of() : request.businessKeys();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("issueKey", issueKey);
        manifest.put("title", title);
        manifest.put("entity", entitySummary(detail));
        manifest.put("snapshotId", request == null ? null : request.snapshotId());
        manifest.put("reservationId", request == null ? null : request.reservationId());
        manifest.put("privacyAction", privacy);
        manifest.put("recreationMode", mode);
        manifest.put("businessKeys", keys);
        manifest.put("flow", List.of("locate entity", "reserve keys", "snapshot evidence", "mask or synthesize", "package replay"));
        String instructions = """
                1. Confirm the linked reservation/snapshot.
                2. Generate or provision data through the approved execution plan.
                3. Use the package manifest as the replay evidence bundle.
                4. Release reservation when the issue cycle closes.
                """;
        Instant expires = request != null && request.ttlHours() != null && request.ttlHours() > 0
                ? Instant.now().plus(Math.min(request.ttlHours(), 24 * 365), ChronoUnit.HOURS) : null;
        long id = insert("""
                INSERT INTO be_issue_packages(entity_id, issue_key, title, severity, source_environment,
                    target_environment, snapshot_id, reservation_id, recreation_mode, privacy_action, status,
                    business_keys_json, package_manifest_json, replay_instructions, created_by, expires_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)
                """,
                entityId, issueKey, title, blank(request == null ? null : request.severity()),
                blank(request == null ? null : request.sourceEnvironment()), blank(request == null ? null : request.targetEnvironment()),
                request == null ? null : request.snapshotId(), request == null ? null : request.reservationId(),
                mode, privacy, writeJson(keys), writeJson(manifest), instructions, currentUsername(), ts(expires), ts(Instant.now()));
        audit.log("system", "BUSINESS_ENTITY_ISSUE_PACKAGE_CREATE", "entity=" + detail.entity().getName() + " issue=" + issueKey);
        return getIssuePackage(id);
    }

    public Map<String, Object> createLookalikeProfile(Long entityId, LookalikeProfileRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        String name = required(request == null ? null : request.name(), "Profile name");
        long rowGoal = request == null || request.rowGoal() == null ? 1000L : Math.max(1L, request.rowGoal());
        String privacy = upperDefault(request == null ? null : request.privacyMode(), "NO_RAW_VALUES");
        Map<String, Object> plan = lookalikePlan(detail, rowGoal, request == null ? null : request.objective());
        Map<String, Object> safety = Map.of(
                "privacyMode", privacy,
                "samplePolicy", "METADATA_ONLY",
                "rawValuesStored", false,
                "bankingGuardrail", "No source row samples are stored in the profile; only metadata and generator choices are persisted.",
                "warnings", List.of("Review cross-column business rules before very high-volume UAT loads."));
        long id = insert("""
                INSERT INTO be_lookalike_profiles(entity_id, name, objective, privacy_mode, sample_policy,
                    row_goal, generator_plan_json, safety_report_json, status, created_by, updated_at)
                VALUES (?, ?, ?, ?, 'METADATA_ONLY', ?, ?, ?, 'DRAFT', ?, ?)
                """,
                entityId, name, blank(request == null ? null : request.objective()), privacy, rowGoal,
                writeJson(plan), writeJson(safety), currentUsername(), ts(Instant.now()));
        audit.log("system", "BUSINESS_ENTITY_LOOKALIKE_PROFILE_CREATE", "entity=" + detail.entity().getName() + " profile=" + name);
        return getLookalikeProfile(id);
    }

    public Map<String, Object> syncCatalog(Long entityId) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        List<String> currentQualifiedNames = new ArrayList<>();
        String entityQualifiedName = "be://" + detail.entity().getId();
        currentQualifiedNames.add(entityQualifiedName);
        upsertCatalog(entityId, "BUSINESS_ENTITY", detail.entity().getId(),
                entityQualifiedName, detail.entity().getName(), detail.entity().getOwnerUsername(),
                detail.entity().getDomain(), "business-entity," + safe(detail.entity().getDomain()),
                "CERTIFIED", Map.of("rootTable", safe(detail.entity().getRootTable())),
                Map.of("members", detail.members().stream().map(BusinessEntityMemberEntity::getTableName).toList()),
                0.92);
        for (BusinessEntityMemberEntity m : detail.members()) {
            double quality = (m.getDataSourceId() == null ? 0 : .25) + (m.getKeyColumns() == null ? 0 : .35)
                    + (m.getJoinToRole() == null ? .15 : .25) + .15;
            String memberQualifiedName = "be://" + detail.entity().getId() + "/member/"
                    + pathSegment(m.getLogicalRole()) + "/" + pathSegment(m.getTableName());
            currentQualifiedNames.add(memberQualifiedName);
            upsertCatalog(entityId, "ENTITY_MEMBER", m.getId(),
                    memberQualifiedName,
                    m.getLogicalRole() + " / " + m.getTableName(), detail.entity().getOwnerUsername(),
                    detail.entity().getDomain(), "member," + safe(m.getSystemName()),
                    quality >= .75 ? "CERTIFIED" : "NEEDS_REVIEW",
                    Map.of("dataSourceId", String.valueOf(m.getDataSourceId()), "schema", safe(m.getSchemaName())),
                    Map.of("joinToRole", safe(m.getJoinToRole()), "datasetId", String.valueOf(m.getDatasetId())),
                    Math.round(quality * 100.0) / 100.0);
        }
        deleteStaleCatalogAssets(entityId, currentQualifiedNames);
        audit.log("system", "BUSINESS_ENTITY_CATALOG_SYNC", "entity=" + detail.entity().getName());
        return dashboard(entityId);
    }

    public Map<String, Object> createGovernanceRequest(Long entityId, GovernanceRequestRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        String objectType = upperDefault(request == null ? null : request.objectType(), "BUSINESS_ENTITY");
        Long objectId = request == null || request.objectId() == null ? entityId : request.objectId();
        String action = upperDefault(request == null ? null : request.action(), "RELEASE");
        String requestedBy = currentUsername();
        String riskLevel = upperDefault(request == null ? null : request.riskLevel(), "MEDIUM");
        String reviewer = blank(request == null ? null : request.reviewer());
        String comments = blank(request == null ? null : request.comments());
        Map<String, Object> risk = Map.of(
                "riskLevel", riskLevel,
                "makerCheckerRequired", true,
                "objectType", objectType,
                "action", action,
                "retentionRequired", true);
        long id = insert("""
                INSERT INTO be_governance_requests(entity_id, object_type, object_id, action, requested_by,
                    reviewer, status, risk_level, risk_json, evidence_json, comments, due_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                """,
                entityId, objectType, objectId, action, requestedBy, reviewer,
                riskLevel, writeJson(risk), writeJson(Map.of("createdFrom", "BusinessEntityEnterpriseService")),
                comments, ts(Instant.now().plus(7, ChronoUnit.DAYS)), ts(Instant.now()));
        audit.record(requestedBy, "BUSINESS_ENTITY_GOVERNANCE_REQUEST", "GOVERNANCE",
                "business-entity-governance-request", String.valueOf(id), detail.entity().getName() + " / " + action,
                "SUCCESS", "Created governance request for " + objectType + " " + objectId,
                auditMetadata(
                        "entityId", entityId,
                        "objectType", objectType,
                        "objectId", objectId,
                        "governanceAction", action,
                        "riskLevel", riskLevel,
                        "reviewerAssigned", reviewer != null,
                        "commentsPresent", comments != null));
        return getGovernanceRequest(id);
    }

    public Map<String, Object> approveGovernanceRequest(Long id, DecisionRequest decision) {
        Map<String, Object> row = getGovernanceRequest(id);
        String actor = authenticatedDecisionActor(decision);
        String requestedBy = String.valueOf(row.get("requestedBy"));
        if (actor.equalsIgnoreCase(requestedBy)) {
            throw ApiException.conflict("Maker-checker violation: requester cannot approve their own governance request.");
        }
        updateDecision(id, "APPROVED", actor, decision == null ? null : decision.comments(), decision == null ? null : decision.eSignature());
        audit.record(actor, "BUSINESS_ENTITY_GOVERNANCE_APPROVED", "GOVERNANCE",
                "business-entity-governance-request", String.valueOf(id), row.get("action") + " / " + row.get("objectType"),
                "SUCCESS", "Approved governance request " + id,
                governanceDecisionMetadata(row, "APPROVED", decision));
        return getGovernanceRequest(id);
    }

    public Map<String, Object> rejectGovernanceRequest(Long id, DecisionRequest decision) {
        Map<String, Object> row = getGovernanceRequest(id);
        String actor = authenticatedDecisionActor(decision);
        updateDecision(id, "REJECTED", actor, decision == null ? null : decision.comments(), decision == null ? null : decision.eSignature());
        audit.record(actor, "BUSINESS_ENTITY_GOVERNANCE_REJECTED", "GOVERNANCE",
                "business-entity-governance-request", String.valueOf(id), row.get("action") + " / " + row.get("objectType"),
                "SUCCESS", "Rejected governance request " + id,
                governanceDecisionMetadata(row, "REJECTED", decision));
        return getGovernanceRequest(id);
    }

    public Map<String, Object> createExecutionPlan(Long entityId, ExecutionPlanRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        String operation = upperDefault(request == null ? null : request.operationType(), "SUBSET_MASK");
        String name = required(request == null ? null : request.name(), operation + " " + detail.entity().getName());
        Long approved = latestApprovedRequest(entityId, operation);
        List<Map<String, Object>> strategies = loaderStrategies(detail);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("entity", entitySummary(detail));
        plan.put("operationType", operation);
        plan.put("mode", upperDefault(request == null ? null : request.mode(), "PLAN_ONLY"));
        plan.put("sourceEnvironment", blank(request == null ? null : request.sourceEnvironment()));
        plan.put("targetEnvironment", blank(request == null ? null : request.targetEnvironment()));
        plan.put("issuePackageId", request == null ? null : request.issuePackageId());
        plan.put("lookalikeProfileId", request == null ? null : request.lookalikeProfileId());
        plan.put("members", detail.members().stream().map(this::memberPlan).toList());
        plan.put("applicationSlices", isDataScopeOperation(operation)
                ? dataScopeFanOutPlan(detail, null, false).stream().map(this::slicePlan).toList()
                : List.of());
        plan.put("executionOrder", detail.members().stream().map(BusinessEntityMemberEntity::getLogicalRole).toList());
        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("requiresApproval", approved == null);
        validation.put("approvedRequestId", approved);
        validation.put("preflightChecks", List.of("catalog synced", "reservation conflict check", "PII policy coverage", "target loader availability"));
        // Micro-DB governance gate: a plan can (or, when configured, MUST) attach an ACTIVE capsule
        // the requester holds a valid access grant on. Verified here, recorded as plan evidence.
        Long capsuleInstanceId = request == null ? null : request.capsuleInstanceId();
        if (capsuleInstanceId == null && props.getGovernance().isRequireCapsuleOnExecutionPlans()) {
            throw ApiException.bad("Governance requires a Micro-DB capsule on execution plans: materialize a capsule "
                    + "for this entity and pass capsuleInstanceId.");
        }
        if (capsuleInstanceId != null) {
            validation.put("capsuleEvidence", capsules.planAttachmentEvidence(entityId, capsuleInstanceId));
        }
        long id = insert("""
                INSERT INTO be_entity_execution_plans(entity_id, name, operation_type, source_environment,
                    target_environment, mode, status, issue_package_id, lookalike_profile_id, approved_request_id,
                    plan_json, validation_json, loader_strategy_json, created_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entityId, name, operation, blank(request == null ? null : request.sourceEnvironment()),
                blank(request == null ? null : request.targetEnvironment()), upperDefault(request == null ? null : request.mode(), "PLAN_ONLY"),
                approved == null ? "READY_FOR_APPROVAL" : "APPROVED", request == null ? null : request.issuePackageId(),
                request == null ? null : request.lookalikeProfileId(), approved, writeJson(plan), writeJson(validation),
                writeJson(strategies), currentUsername(), ts(Instant.now()));
        audit.log("system", "BUSINESS_ENTITY_EXECUTION_PLAN_CREATE", "entity=" + detail.entity().getName() + " plan=" + id);
        return getExecutionPlan(id);
    }

    public Map<String, Object> launchExecutionPlan(Long planId, LaunchRequest request) {
        Map<String, Object> plan = getExecutionPlan(planId);
        String status = safe((String) plan.get("status")).toUpperCase(Locale.ROOT);
        if (!"APPROVED".equals(status) && !"SUBMITTED".equals(status)) {
            throw ApiException.conflict("Execution plan must be approved before launch.");
        }
        Long entityId = num(plan.get("entityId"));
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        Long capsuleInstanceId = attachedCapsuleId(plan);
        if (capsuleInstanceId != null) {
            return launchCapsulePlan(detail, plan, request, capsuleInstanceId);
        }
        String operation = safe((String) plan.get("operationType")).toUpperCase(Locale.ROOT);
        return switch (operation) {
            case "SUBSET_MASK", "ISSUE_RECREATE" -> launchDataScopePlan(detail, plan, request);
            case "SYNTHETIC_LOOKALIKE" -> launchSyntheticLookalikePlan(detail, plan, request);
            default -> throw ApiException.bad("Unsupported Business Entity execution operation: " + operation);
        };
    }

    private Map<String, Object> launchCapsulePlan(BusinessEntityService.BusinessEntityDetail detail,
                                                   Map<String, Object> plan,
                                                   LaunchRequest request,
                                                   Long capsuleInstanceId) {
        if (request == null || request.targetDataSourceId() == null) {
            throw ApiException.bad("A capsule-backed plan requires targetDataSourceId; the source systems are not read during this run.");
        }
        String prep = upperDefault(request.targetPrep(), "DELETE");
        boolean deleteExistingByKey = !"APPEND".equals(prep) && !"INSERT".equals(prep);
        Map<String, Object> provisioned = capsules.provisionToTarget(capsuleInstanceId,
                new BusinessEntityCapsuleService.ProvisionFromCapsuleRequest(
                        request.targetDataSourceId(), blank(request.targetSchema()), deleteExistingByKey));
        String runId = "microdb-" + capsuleInstanceId + "-v" + provisioned.get("version")
                + "-" + Instant.now().toEpochMilli();
        Map<String, Object> result = new LinkedHashMap<>(provisioned);
        result.put("engine", "MICRO_DB");
        result.put("runId", runId);
        result.put("status", "COMPLETED");
        result.put("sourceRead", false);
        result.put("message", "Provisioned from encrypted Micro-DB capsule #" + capsuleInstanceId
                + "; live source applications were not queried.");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("strategy", "MICRO_DB_ENCRYPTED_FRAGMENTS");
        evidence.put("capsuleInstanceId", capsuleInstanceId);
        evidence.put("capsuleVersion", provisioned.get("version"));
        evidence.put("sourceRead", false);
        recordExecutionRun(detail.entity().getId(), num(plan.get("id")), "MICRO_DB", runId,
                "COMPLETED", request, result, evidence);
        jdbc.update("UPDATE be_entity_execution_plans SET updated_at = ? WHERE id = ?",
                ts(Instant.now()), plan.get("id"));
        audit.log(currentUsername(), "BUSINESS_ENTITY_MICRO_DB_PLAN_LAUNCH",
                "plan=" + plan.get("id") + " capsule=" + capsuleInstanceId + " target=" + request.targetDataSourceId());
        return result;
    }

    private Long attachedCapsuleId(Map<String, Object> plan) {
        JsonNode validation = readJson(plan.get("validationJson"), "execution plan validation");
        JsonNode value = validation.path("capsuleEvidence").path("capsuleInstanceId");
        return value.isIntegralNumber() ? value.longValue() : null;
    }

    public Map<String, Object> createOperationalPackage(Long entityId, OperationalPackageRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        Long planId = request == null ? null : request.executionPlanId();
        if (planId == null) throw ApiException.bad("executionPlanId is required");
        Map<String, Object> plan = getExecutionPlan(planId);
        String name = required(request.name(), "Operational package");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("entity", entitySummary(detail));
        manifest.put("executionPlanId", planId);
        manifest.put("targetEnvironment", safe(request.targetEnvironment()));
        manifest.put("auth", "FORGETDM_TOKEN bearer token");
        manifest.put("schedulerContract", Map.of("idempotent", true, "requiresApproval", true, "supportsHealthCheck", true));
        Map<String, Object> health = Map.of(
                "checks", List.of("/api/auth/me", "/api/business-entities/" + entityId, "/api/business-entities/" + entityId + "/enterprise"),
                "expectedPlanStatus", plan.get("status"));
        long id = insert("""
                INSERT INTO be_operational_packages(entity_id, execution_plan_id, package_type, name, status,
                    manifest_json, shell_script, health_check_json, promotion_json, created_by, updated_at)
                VALUES (?, ?, ?, ?, 'READY', ?, ?, ?, ?, ?, ?)
                """,
                entityId, planId, upperDefault(request.packageType(), "SCHEDULER_RUNNER"), name, writeJson(manifest),
                "pending", writeJson(health), writeJson(Map.of("from", "DEV", "to", safe(request.targetEnvironment()))),
                currentUsername(), ts(Instant.now()));
        String script = runnerScript(id, entityId, planId, name);
        jdbc.update("UPDATE be_operational_packages SET shell_script = ?, updated_at = ? WHERE id = ?", script, ts(Instant.now()), id);
        createPackageVersion(id, new PackageVersionRequest("STANDARD_7_YEAR", 2555, "Initial immutable package artifact"));
        audit.log("system", "BUSINESS_ENTITY_OPERATIONAL_PACKAGE_CREATE", "entity=" + detail.entity().getName() + " package=" + id);
        return getOperationalPackage(id);
    }

    public Map<String, Object> getOperationalPackage(Long id) {
        Map<String, Object> pkg = one("""
                SELECT id, entity_id AS "entityId", execution_plan_id AS "executionPlanId", package_type AS "packageType",
                       name, status, manifest_json AS "manifestJson", shell_script AS "shellScript",
                       health_check_json AS "healthCheckJson", promotion_json AS "promotionJson",
                       created_by AS "createdBy", updated_at AS "updatedAt"
                  FROM be_operational_packages WHERE id = ?
                """, id);
        pkg.put("versions", listPackageVersions(id));
        pkg.put("promotions", listPackagePromotions(id));
        return pkg;
    }

    public List<Map<String, Object>> listPackageVersions(Long packageId) {
        return rows("""
                SELECT id, package_id AS "packageId", entity_id AS "entityId", version_number AS "versionNumber",
                       status, artifact_hash AS "artifactHash", manifest_json AS "manifestJson",
                       shell_script AS "shellScript", immutable_manifest_json AS "immutableManifestJson",
                       retention_policy AS "retentionPolicy", retention_until AS "retentionUntil",
                       created_by AS "createdBy", created_at AS "createdAt"
                  FROM be_operational_package_versions
                 WHERE package_id = ? ORDER BY version_number DESC
                """, packageId);
    }

    public List<Map<String, Object>> listPackagePromotions(Long packageId) {
        return rows("""
                SELECT id, package_id AS "packageId", version_id AS "versionId",
                       from_environment AS "fromEnvironment", to_environment AS "toEnvironment", status,
                       approved_request_id AS "approvedRequestId", evidence_json AS "evidenceJson",
                       requested_by AS "requestedBy", approved_by AS "approvedBy", promoted_at AS "promotedAt",
                       created_at AS "createdAt", updated_at AS "updatedAt"
                  FROM be_operational_package_promotions
                 WHERE package_id = ? ORDER BY created_at DESC
                """, packageId);
    }

    public Map<String, Object> createPackageVersion(Long packageId, PackageVersionRequest request) {
        Map<String, Object> pkg = getOperationalPackageRaw(packageId);
        Long entityId = num(pkg.get("entityId"));
        String createdBy = currentUsername();
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) FROM be_operational_package_versions WHERE package_id = ?",
                Integer.class, packageId);
        int version = (max == null ? 0 : max) + 1;
        String retentionPolicy = upperDefault(request == null ? null : request.retentionPolicy(), "STANDARD_7_YEAR");
        int retentionDays = request == null || request.retentionDays() == null ? 2555 : Math.max(1, request.retentionDays());
        Instant retentionUntil = Instant.now().plus(retentionDays, ChronoUnit.DAYS);
        Map<String, Object> immutable = new LinkedHashMap<>();
        immutable.put("packageId", packageId);
        immutable.put("executionPlanId", pkg.get("executionPlanId"));
        immutable.put("versionNumber", version);
        immutable.put("retentionPolicy", retentionPolicy);
        immutable.put("retentionUntil", retentionUntil.toString());
        immutable.put("changeNote", blank(request == null ? null : request.changeNote()));
        immutable.put("createdBy", createdBy);
        String artifact = String.join("\n---FORGETDM-ARTIFACT---\n",
                String.valueOf(pkg.get("manifestJson")), String.valueOf(pkg.get("shellScript")),
                String.valueOf(pkg.get("healthCheckJson")), String.valueOf(pkg.get("promotionJson")),
                writeJson(immutable));
        String hash = sha256(artifact);
        immutable.put("artifactHash", hash);
        long id = insert("""
                INSERT INTO be_operational_package_versions(package_id, entity_id, version_number, status,
                    artifact_hash, manifest_json, shell_script, health_check_json, promotion_json,
                    immutable_manifest_json, retention_policy, retention_until, created_by)
                VALUES (?, ?, ?, 'IMMUTABLE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                packageId, entityId, version, hash, pkg.get("manifestJson"), pkg.get("shellScript"),
                pkg.get("healthCheckJson"), pkg.get("promotionJson"), writeJson(immutable),
                retentionPolicy, ts(retentionUntil), createdBy);
        jdbc.update("UPDATE be_operational_packages SET updated_at = ? WHERE id = ?", ts(Instant.now()), packageId);
        audit.record(createdBy, "BUSINESS_ENTITY_PACKAGE_VERSION_CREATE", "RELEASE",
                "business-entity-package-version", String.valueOf(id), pkg.get("name") + " v" + version,
                "SUCCESS", "Created immutable package version " + version,
                auditMetadata(
                        "entityId", entityId,
                        "packageId", packageId,
                        "executionPlanId", pkg.get("executionPlanId"),
                        "versionNumber", version,
                        "artifactHash", hash,
                        "retentionPolicy", retentionPolicy,
                        "retentionUntil", retentionUntil.toString(),
                        "changeNotePresent", blank(request == null ? null : request.changeNote()) != null));
        return getPackageVersion(id);
    }

    public Map<String, Object> promotePackageVersion(Long packageId, PromotionRequest request) {
        Map<String, Object> pkg = getOperationalPackageRaw(packageId);
        Long versionId = request == null || request.versionId() == null
                ? latestPackageVersionId(packageId) : request.versionId();
        if (versionId == null) throw ApiException.bad("Create a package version before promotion.");
        Map<String, Object> version = getPackageVersion(versionId);
        if (!Objects.equals(num(version.get("packageId")), packageId)) throw ApiException.bad("Package version does not belong to package " + packageId);
        Long entityId = num(pkg.get("entityId"));
        String requestedBy = currentUsername();
        String from = blankToDefault(request == null ? null : request.fromEnvironment(), "DEV");
        String to = required(request == null ? null : request.toEnvironment(), "Target environment");
        Long approved = latestApprovedRequest(entityId, "PROMOTE");
        String approvedBy = null;
        if (approved != null) {
            Object signedBy = getGovernanceRequest(approved).get("signedBy");
            approvedBy = signedBy == null ? null : blank(String.valueOf(signedBy));
            String suppliedApprover = blank(request == null ? null : request.approver());
            if (suppliedApprover != null && approvedBy != null && !suppliedApprover.equalsIgnoreCase(approvedBy)) {
                throw ApiException.bad("Approver must match the signer of the approved governance request.");
            }
        }
        String status = approved == null ? "READY_FOR_APPROVAL" : "PROMOTED";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("packageId", packageId);
        evidence.put("versionId", versionId);
        evidence.put("artifactHash", version.get("artifactHash"));
        evidence.put("fromEnvironment", from);
        evidence.put("toEnvironment", to);
        evidence.put("approvedRequestId", approved);
        evidence.put("comments", blank(request == null ? null : request.comments()));
        evidence.put("immutableRetentionUntil", version.get("retentionUntil"));
        long id = insert("""
                INSERT INTO be_operational_package_promotions(package_id, entity_id, version_id,
                    from_environment, to_environment, status, approved_request_id, evidence_json,
                    requested_by, approved_by, promoted_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                packageId, entityId, versionId, from, to, status, approved, writeJson(evidence),
                requestedBy, approvedBy,
                approved == null ? null : ts(Instant.now()), ts(Instant.now()));
        jdbc.update("UPDATE be_operational_packages SET status = ?, promotion_json = ?, updated_at = ? WHERE id = ?",
                approved == null ? "PROMOTION_APPROVAL_REQUIRED" : "PROMOTED",
                writeJson(evidence), ts(Instant.now()), packageId);
        audit.record(requestedBy, "BUSINESS_ENTITY_PACKAGE_PROMOTION", "RELEASE",
                "business-entity-package-promotion", String.valueOf(id), pkg.get("name") + " / " + from + " -> " + to,
                "SUCCESS", "Package promotion status " + status,
                auditMetadata(
                        "entityId", entityId,
                        "packageId", packageId,
                        "versionId", versionId,
                        "artifactHash", version.get("artifactHash"),
                        "fromEnvironment", from,
                        "toEnvironment", to,
                        "promotionStatus", status,
                        "approvedRequestId", approved,
                        "governanceApprovedBy", approvedBy,
                        "commentsPresent", blank(request == null ? null : request.comments()) != null));
        return getPackagePromotion(id);
    }

    private Map<String, Object> launchDataScopePlan(BusinessEntityService.BusinessEntityDetail detail,
                                                    Map<String, Object> plan,
                                                    LaunchRequest request) {
        List<DataScopeFanOutSlice> slices = dataScopeFanOutPlan(detail, request, true);
        if (slices.size() > 1) {
            return launchDataScopeFanOut(detail, plan, request, slices);
        }
        return launchDataScopeSlice(detail, plan, request, slices.get(0), null, 1, 1, "DATASCOPE");
    }

    private Map<String, Object> launchDataScopeFanOut(BusinessEntityService.BusinessEntityDetail detail,
                                                      Map<String, Object> plan,
                                                      LaunchRequest request,
                                                      List<DataScopeFanOutSlice> slices) {
        String fanOutId = "be-" + detail.entity().getId() + "-plan-" + plan.get("id") + "-" + Instant.now().toEpochMilli();
        List<Map<String, Object>> childRuns = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("engine", "DATASCOPE_FANOUT");
        result.put("runId", fanOutId);
        result.put("fanOut", true);
        result.put("sliceCount", slices.size());
        result.put("slices", slices.stream().map(this::slicePlan).toList());
        try {
            for (int i = 0; i < slices.size(); i++) {
                childRuns.add(launchDataScopeSlice(detail, plan, request, slices.get(i), fanOutId, i + 1, slices.size(), "DATASCOPE"));
            }
            result.put("runs", childRuns);
            result.put("status", aggregateFanOutStatus(childRuns));
            result.put("message", "Submitted " + childRuns.size() + " DataScope application slice run(s).");
            recordExecutionRun(detail.entity().getId(), num(plan.get("id")), "DATASCOPE_FANOUT",
                    fanOutId, String.valueOf(result.get("status")), request, result, fanOutLoaderEvidence(slices, childRuns));
            jdbc.update("UPDATE be_entity_execution_plans SET updated_at = ? WHERE id = ?", ts(Instant.now()), plan.get("id"));
            audit.log("system", "BUSINESS_ENTITY_EXECUTION_PLAN_FANOUT_LAUNCH",
                    "plan=" + plan.get("id") + " fanOut=" + fanOutId + " slices=" + slices.size());
            return result;
        } catch (RuntimeException ex) {
            result.put("runs", childRuns);
            result.put("status", "FAILED");
            result.put("message", "Fan-out launch failed after " + childRuns.size() + " submitted slice(s): " + ex.getMessage());
            recordExecutionRun(detail.entity().getId(), num(plan.get("id")), "DATASCOPE_FANOUT",
                    fanOutId, "FAILED", request, result, fanOutLoaderEvidence(slices, childRuns));
            throw ex;
        }
    }

    private Map<String, Object> launchDataScopeSlice(BusinessEntityService.BusinessEntityDetail detail,
                                                     Map<String, Object> plan,
                                                     LaunchRequest request,
                                                     DataScopeFanOutSlice slice,
                                                     String fanOutId,
                                                     int sliceNo,
                                                     int sliceCount,
                                                     String engineName) {
        DataSetDefinitionEntity def = slice.dataset();
        DataSourceEntity target = slice.target();
        NativeLoadStrategy strategy = slice.strategy();
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("driverTable", blankToDefault(def.getDriverTable(), detail.entity().getRootTable()));
        spec.put("filter", blankToDefault(request == null ? null : request.filter(), def.getDriverFilter()));
        spec.put("maxDriverRows", request == null || request.maxRows() == null ? 0 : Math.max(0, request.maxRows()));
        spec.put("sourceSchema", def.getSchemaName());
        spec.put("targetSchema", blankToDefault(request == null ? null : request.targetSchema(), def.getTargetSchemaName()));
        spec.put("maskingSeed", blankToDefault(request == null ? null : request.maskingSeed(),
                request == null || request.seed() == null ? null : String.valueOf(request.seed())));
        spec.put("loadAction", upperDefault(request == null ? null : request.loadAction(), "REPLACE"));
        String dataScopePrep = upperDefault(request == null ? null : request.targetPrep(), "DELETE");
        if ("TRUNCATE_CASCADE".equals(dataScopePrep)) dataScopePrep = "TRUNCATE";
        spec.put("targetPrep", dataScopePrep);
        spec.put("batchSize", "POSTGRES_COPY".equals(strategy.strategy()) ? 50000 : 10000);
        spec.put("businessEntityId", detail.entity().getId());
        spec.put("executionPlanId", plan.get("id"));
        spec.put("applicationSlice", slice.sliceKey());
        spec.put("applicationSliceLabel", slice.label());
        spec.put("applicationSliceNo", sliceNo);
        spec.put("applicationSliceCount", sliceCount);
        spec.put("applicationSliceMembers", slice.members().stream().map(this::memberPlan).toList());
        if (fanOutId != null) spec.put("fanOutRunId", fanOutId);
        spec.put("loaderStrategy", strategy.strategy());
        spec.put("effectiveLoaderStrategy", strategy.nativeAvailable() ? strategy.strategy() : strategy.fallback());
        spec.put("nativeLoader", loaders.asMap(strategy));

        ProvisionJobEntity job = new ProvisionJobEntity();
        String suffix = sliceCount > 1 ? " / " + slice.label() + " (" + sliceNo + "/" + sliceCount + ")" : "";
        job.setName(safe((String) plan.get("name")) + " DataScope run" + suffix);
        job.setJobType("SUBSET_MASK");
        job.setSourceId(def.getDataSourceId());
        job.setTargetId(slice.targetDataSourceId());
        job.setPolicyId(firstNonNull(request == null ? null : request.policyId(), def.getPolicyId()));
        job.setDatasetId(def.getId());
        job.setSpecJson(writeJson(spec));

        ProvisionJobEntity submitted = provisioning.submit(job);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("engine", engineName);
        result.put("runId", submitted.getId());
        result.put("status", submitted.getStatus());
        result.put("approvalStatus", submitted.getApprovalStatus());
        result.put("message", submitted.getMessage());
        result.put("fanOutRunId", fanOutId);
        result.put("sliceNo", sliceNo);
        result.put("sliceCount", sliceCount);
        result.put("slice", slicePlan(slice));
        result.put("sourceDataSourceId", def.getDataSourceId());
        result.put("targetDataSourceId", slice.targetDataSourceId());
        result.put("targetDataSourceName", target.getName());
        result.put("loaderStrategy", loaders.asMap(strategy));
        recordExecutionRun(detail.entity().getId(), num(plan.get("id")), "DATASCOPE",
                submitted.getId() == null ? null : String.valueOf(submitted.getId()), submitted.getStatus(),
                request, result, strategy);
        jdbc.update("UPDATE be_entity_execution_plans SET updated_at = ? WHERE id = ?", ts(Instant.now()), plan.get("id"));
        audit.log("system", "BUSINESS_ENTITY_EXECUTION_PLAN_LAUNCH",
                "plan=" + plan.get("id") + " engine=DATASCOPE slice=" + slice.sliceKey() + " run=" + submitted.getId());
        return result;
    }

    private Map<String, Object> launchSyntheticLookalikePlan(BusinessEntityService.BusinessEntityDetail detail,
                                                             Map<String, Object> plan,
                                                             LaunchRequest request) {
        Long profileId = firstNonNull(request == null ? null : request.lookalikeProfileId(), num(plan.get("lookalikeProfileId")));
        if (profileId == null) throw ApiException.bad("Choose a look-alike profile before launching synthetic generation.");
        Map<String, Object> profile = getLookalikeProfile(profileId);
        Long targetId = request == null ? null : request.targetDataSourceId();
        if (targetId == null) throw ApiException.bad("Choose a target data source before launching synthetic generation.");
        DataSourceEntity target = dataSources.findById(targetId)
                .orElseThrow(() -> ApiException.notFound("Target data source " + targetId + " not found"));
        NativeLoadStrategy strategy = loaders.strategyFor(target);
        Long seed = request == null || request.seed() == null ? stableSeed(detail.entity().getName(), profileId) : request.seed();
        Long rowCount = request == null || request.rowCount() == null
                ? Math.max(1L, Optional.ofNullable(num(profile.get("rowGoal"))).orElse(1000L))
                : Math.max(1L, request.rowCount());

        SyntheticGenService.GenPlan genPlan = new SyntheticGenService.GenPlan(
                safe(detail.entity().getName()) + "-plan-" + plan.get("id"),
                syntheticTables(profile, rowCount),
                seed,
                "DB",
                targetId,
                request == null ? null : request.targetSchema(),
                true,
                false,
                null,
                upperDefault(request == null ? null : request.loadAction(), "REPLACE"),
                upperDefault(request == null ? null : request.targetPrep(), "DELETE"),
                List.of(),
                5000,
                0,
                false,
                1000,
                strategy.nativeAvailable() || "POSTGRES_COPY".equals(strategy.strategy()),
                upperDefault(request == null ? null : request.executionMode(), "SINGLE"),
                null,
                null,
                null);

        Map<String, Object> started = synthetic.startGenerate(genPlan);
        String runId = String.valueOf(started.getOrDefault("id", started.getOrDefault("runId", "")));
        Map<String, Object> result = new LinkedHashMap<>(started);
        result.put("engine", "SYNTHETIC");
        result.put("runId", runId);
        result.put("loaderStrategy", loaders.asMap(strategy));
        recordExecutionRun(detail.entity().getId(), num(plan.get("id")), "SYNTHETIC",
                runId.isBlank() ? null : runId, String.valueOf(started.getOrDefault("status", "PENDING")),
                request, result, strategy);
        jdbc.update("UPDATE be_entity_execution_plans SET updated_at = ? WHERE id = ?", ts(Instant.now()), plan.get("id"));
        audit.log("system", "BUSINESS_ENTITY_EXECUTION_PLAN_LAUNCH",
                "plan=" + plan.get("id") + " engine=SYNTHETIC run=" + runId);
        return result;
    }

    private List<Map<String, Object>> loaderStrategies(BusinessEntityService.BusinessEntityDetail detail) {
        Map<Long, DataSourceEntity> byId = dataSources.findAllById(detail.members().stream()
                .map(BusinessEntityMemberEntity::getDataSourceId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(DataSourceEntity::getId, d -> d));
        List<Map<String, Object>> out = new ArrayList<>();
        for (BusinessEntityMemberEntity m : detail.members()) {
            DataSourceEntity ds = byId.get(m.getDataSourceId());
            String kind = ds == null ? "GENERIC" : String.valueOf(ds.getKind()).toUpperCase(Locale.ROOT);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", m.getId());
            row.put("role", m.getLogicalRole());
            row.put("table", m.getTableName());
            row.put("dataSourceId", m.getDataSourceId());
            NativeLoadStrategy strategy = loaders.strategyFor(ds);
            row.put("engine", kind);
            row.put("strategy", strategy.strategy());
            row.put("nativeAvailable", strategy.nativeAvailable());
            row.put("executor", strategy.executor());
            row.put("enterpriseReady", strategy.nativeAvailable() || !kind.equals("GENERIC"));
            row.put("fallback", strategy.fallback());
            row.put("launchMode", strategy.launchMode());
            row.put("configureHint", strategy.configureHint());
            row.put("notes", strategy.notes());
            out.add(row);
        }
        return out;
    }

    private List<DataScopeFanOutSlice> dataScopeFanOutPlan(BusinessEntityService.BusinessEntityDetail detail,
                                                           LaunchRequest request,
                                                           boolean requireTarget) {
        Map<Long, List<BusinessEntityMemberEntity>> byDataset = new LinkedHashMap<>();
        Long primaryDatasetId = detail.entity().getPrimaryDatasetId();
        for (BusinessEntityMemberEntity member : detail.members()) {
            if (!member.isIncludeInSubset()) continue;
            Long datasetId = firstNonNull(member.getDatasetId(), primaryDatasetId);
            if (datasetId == null) continue;
            byDataset.computeIfAbsent(datasetId, k -> new ArrayList<>()).add(member);
        }
        if (byDataset.isEmpty() && primaryDatasetId != null) {
            byDataset.put(primaryDatasetId, List.of());
        }
        if (byDataset.isEmpty()) {
            throw ApiException.bad("This Business Entity needs at least one DataScope blueprint before a subset/mask launch.");
        }

        List<DataScopeFanOutSlice> slices = new ArrayList<>();
        for (Map.Entry<Long, List<BusinessEntityMemberEntity>> entry : byDataset.entrySet()) {
            Long datasetId = entry.getKey();
            DataSetDefinitionEntity def = datasets.findById(datasetId)
                    .orElseThrow(() -> ApiException.notFound("DataScope blueprint " + datasetId + " not found"));
            Long targetId = firstNonNull(request == null ? null : request.targetDataSourceId(), def.getTargetDataSourceId());
            if (targetId == null) {
                if (!requireTarget) {
                    NativeLoadStrategy strategy = loaders.strategyFor(null);
                    slices.add(new DataScopeFanOutSlice(
                            "dataset-" + datasetId + "-target-unresolved",
                            sliceLabel(def, entry.getValue()),
                            def,
                            null,
                            null,
                            strategy,
                            List.copyOf(entry.getValue())));
                    continue;
                }
                throw ApiException.bad("Choose a target data source, or configure target DB on DataScope blueprint '" + def.getName() + "'.");
            }
            DataSourceEntity target = dataSources.findById(targetId)
                    .orElseThrow(() -> ApiException.notFound("Target data source " + targetId + " not found"));
            NativeLoadStrategy strategy = loaders.strategyFor(target);
            String label = sliceLabel(def, entry.getValue());
            slices.add(new DataScopeFanOutSlice(
                    "dataset-" + datasetId + "-target-" + targetId,
                    label,
                    def,
                    targetId,
                    target,
                    strategy,
                    List.copyOf(entry.getValue())));
        }
        return slices;
    }

    private Map<String, Object> slicePlan(DataScopeFanOutSlice slice) {
        Map<String, Object> row = new LinkedHashMap<>();
        DataSetDefinitionEntity def = slice.dataset();
        row.put("sliceKey", slice.sliceKey());
        row.put("label", slice.label());
        row.put("datasetId", def.getId());
        row.put("datasetName", def.getName());
        row.put("sourceDataSourceId", def.getDataSourceId());
        row.put("sourceSchema", def.getSchemaName());
        row.put("targetDataSourceId", slice.targetDataSourceId());
        row.put("targetDataSourceName", slice.target() == null ? null : slice.target().getName());
        row.put("targetSchema", def.getTargetSchemaName());
        row.put("driverTable", def.getDriverTable());
        row.put("memberCount", slice.members().size());
        row.put("members", slice.members().stream().map(m -> {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("memberId", m.getId());
            member.put("role", safe(m.getLogicalRole()));
            member.put("table", safe(m.getTableName()));
            member.put("system", safe(m.getSystemName()));
            return member;
        }).toList());
        row.put("loaderStrategy", loaders.asMap(slice.strategy()));
        return row;
    }

    private Map<String, Object> fanOutLoaderEvidence(List<DataScopeFanOutSlice> slices,
                                                     List<Map<String, Object>> childRuns) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("fanOut", true);
        evidence.put("sliceCount", slices.size());
        evidence.put("slices", slices.stream().map(this::slicePlan).toList());
        evidence.put("childRuns", childRuns.stream().map(r -> {
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("runId", r.get("runId"));
            child.put("status", r.get("status"));
            child.put("sliceNo", r.get("sliceNo"));
            child.put("slice", r.get("slice"));
            return child;
        }).toList());
        return evidence;
    }

    private String aggregateFanOutStatus(List<Map<String, Object>> childRuns) {
        if (childRuns.isEmpty()) return "FAILED";
        boolean approval = false;
        for (Map<String, Object> child : childRuns) {
            String status = safe(String.valueOf(child.get("status"))).toUpperCase(Locale.ROOT);
            String approvalStatus = safe(String.valueOf(child.get("approvalStatus"))).toUpperCase(Locale.ROOT);
            if (Set.of("FAILED", "CANCELED", "REJECTED").contains(status) || "REJECTED".equals(approvalStatus)) return "FAILED";
            if (status.contains("APPROVAL") || approvalStatus.contains("APPROVAL") || "PENDING_APPROVAL".equals(approvalStatus)) {
                approval = true;
            }
        }
        return approval ? "AWAITING_APPROVAL" : "SUBMITTED";
    }

    private String sliceLabel(DataSetDefinitionEntity def, List<BusinessEntityMemberEntity> members) {
        String system = members == null ? null : members.stream()
                .map(BusinessEntityMemberEntity::getSystemName)
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse(null);
        String base = blank(system);
        if (base == null) base = blank(def.getName());
        if (base == null) base = "DataScope " + def.getId();
        return base;
    }

    private static boolean isDataScopeOperation(String operation) {
        String op = safe(operation).toUpperCase(Locale.ROOT);
        return "SUBSET_MASK".equals(op) || "ISSUE_RECREATE".equals(op);
    }

    private void recordExecutionRun(Long entityId, Long planId, String engine, String engineRunId, String engineStatus,
                                    LaunchRequest request, Map<String, Object> result, NativeLoadStrategy strategy) {
        recordExecutionRun(entityId, planId, engine, engineRunId, engineStatus, request, result, loaders.asMap(strategy));
    }

    private void recordExecutionRun(Long entityId, Long planId, String engine, String engineRunId, String engineStatus,
                                    LaunchRequest request, Map<String, Object> result, Map<String, Object> loaderEvidence) {
        long id = insert("""
                INSERT INTO be_execution_plan_runs(entity_id, execution_plan_id, engine, engine_run_id,
                    engine_status, status, launch_request_json, launch_result_json, loader_strategy_json,
                    created_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entityId, planId, engine, blank(engineRunId), blank(engineStatus), runStatus(engineStatus),
                writeJson(request == null ? Map.of() : request), writeJson(result), writeJson(loaderEvidence == null ? Map.of() : loaderEvidence),
                currentUsername(), ts(Instant.now()));
        audit.log("system", "BUSINESS_ENTITY_EXECUTION_RUN_RECORDED", "runRecord=" + id + " plan=" + planId);
    }

    private List<SyntheticGenService.GenTable> syntheticTables(Map<String, Object> profile, long rowCount) {
        JsonNode root = readJson(profile.get("generatorPlanJson"), "look-alike generator plan");
        JsonNode tables = root.path("tables");
        if (!tables.isArray() || tables.isEmpty()) throw ApiException.bad("Look-alike profile has no runnable table plan.");
        List<SyntheticGenService.GenTable> out = new ArrayList<>();
        for (JsonNode table : tables) {
            String tableName = required(text(table, "table", null), "Synthetic table name");
            Set<String> keys = new LinkedHashSet<>(split(text(table, "keyColumns", "")));
            List<SyntheticGenService.GenColumn> cols = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            JsonNode suggestions = table.path("suggestedColumns");
            if (suggestions.isArray()) {
                for (JsonNode col : suggestions) {
                    String name = blank(text(col, "column", null));
                    if (name == null || !seen.add(name.toLowerCase(Locale.ROOT))) continue;
                    String generator = upperDefault(text(col, "generator", null), "LITERAL");
                    cols.add(syntheticColumn(name, generator, keys.contains(name)));
                }
            }
            for (String key : keys) {
                if (key != null && !key.isBlank() && seen.add(key.toLowerCase(Locale.ROOT))) {
                    cols.add(syntheticColumn(key, "SEQUENCE", true));
                }
            }
            if (cols.isEmpty()) cols.add(syntheticColumn("id", "SEQUENCE", true));
            out.add(new SyntheticGenService.GenTable(tableName, rowCount, cols));
        }
        return out;
    }

    private SyntheticGenService.GenColumn syntheticColumn(String name, String generator, boolean primaryKey) {
        String g = upperDefault(generator, "LITERAL");
        String p1 = null, p2 = null, sqlType = "VARCHAR";
        switch (g) {
            case "SEQUENCE" -> sqlType = "BIGINT";
            case "DECIMAL_RANGE", "CURRENCY_USD", "PERCENT", "NORMAL_DECIMAL", "RISK_SCORE" -> {
                sqlType = "DECIMAL(12,2)";
                p1 = "0";
                p2 = "999.99";
            }
            case "DATE_RECENT", "DATE_FUTURE", "DATE_BETWEEN", "DOB_ADULT" -> {
                sqlType = "DATE";
                if ("DATE_RECENT".equals(g) || "DATE_FUTURE".equals(g)) p1 = "365";
            }
            case "TIMESTAMP_RECENT" -> {
                sqlType = "TIMESTAMP";
                p1 = "1440";
            }
            case "AGE", "NORMAL_INT", "INT_RANGE" -> {
                sqlType = "INTEGER";
                p1 = "18";
                p2 = "79";
            }
            case "STATUS" -> p1 = "ACTIVE|INACTIVE|PENDING";
            case "EMAIL", "FIRST_NAME", "LAST_NAME", "FULL_NAME", "USERNAME" -> {
                p1 = "US";
                p2 = "ANY";
            }
            case "PHONE_US" -> sqlType = "VARCHAR(32)";
            case "LITERAL" -> p1 = "";
            default -> {
                if (name.toLowerCase(Locale.ROOT).endsWith("_id")) {
                    g = "SEQUENCE";
                    sqlType = "BIGINT";
                    primaryKey = primaryKey || name.equalsIgnoreCase("id");
                }
            }
        }
        return new SyntheticGenService.GenColumn(name, g, p1, p2, primaryKey, null, null, sqlType, null, null);
    }

    private String runStatus(String engineStatus) {
        String status = safe(engineStatus).toUpperCase(Locale.ROOT);
        if (status.isBlank()) return "SUBMITTED";
        if (Set.of("FAILED", "CANCELED", "COMPLETED", "AWAITING_APPROVAL").contains(status)) return status;
        return "SUBMITTED";
    }

    private Map<String, Object> lookalikePlan(BusinessEntityService.BusinessEntityDetail detail, long rowGoal, String objective) {
        return Map.of(
                "objective", safe(objective),
                "rowGoal", rowGoal,
                "seedPolicy", "deterministic per entity + run seed",
                "tables", detail.members().stream().map(m -> Map.of(
                        "role", m.getLogicalRole(),
                        "table", m.getTableName(),
                        "keyColumns", safe(m.getKeyColumns()),
                        "suggestedColumns", suggestedColumns(m),
                        "relationship", safe(m.getJoinToRole()))).toList());
    }

    private List<Map<String, String>> suggestedColumns(BusinessEntityMemberEntity m) {
        String hay = (m.getLogicalRole() + " " + m.getTableName()).toLowerCase(Locale.ROOT);
        List<Map<String, String>> cols = new ArrayList<>();
        for (String key : split(m.getKeyColumns())) cols.add(Map.of("column", key, "generator", "SEQUENCE"));
        if (hay.contains("customer") || hay.contains("party")) {
            cols.add(Map.of("column", "first_name", "generator", "FIRST_NAME"));
            cols.add(Map.of("column", "last_name", "generator", "LAST_NAME"));
            cols.add(Map.of("column", "email", "generator", "EMAIL"));
            cols.add(Map.of("column", "phone", "generator", "PHONE_US"));
        } else if (hay.contains("account")) {
            cols.add(Map.of("column", "account_status", "generator", "STATUS"));
            cols.add(Map.of("column", "balance", "generator", "DECIMAL_RANGE"));
        } else if (hay.contains("transaction") || hay.contains("payment")) {
            cols.add(Map.of("column", "amount", "generator", "DECIMAL_RANGE"));
            cols.add(Map.of("column", "txn_date", "generator", "DATE_RECENT"));
        } else {
            cols.add(Map.of("column", "status", "generator", "STATUS"));
        }
        return cols;
    }

    private Map<String, Object> entitySummary(BusinessEntityService.BusinessEntityDetail detail) {
        return Map.of("id", detail.entity().getId(), "name", detail.entity().getName(),
                "domain", safe(detail.entity().getDomain()), "rootTable", safe(detail.entity().getRootTable()),
                "members", detail.members().size());
    }

    private Map<String, Object> memberPlan(BusinessEntityMemberEntity m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("memberId", m.getId());
        row.put("role", safe(m.getLogicalRole()));
        row.put("systemName", safe(m.getSystemName()));
        row.put("table", safe(m.getTableName()));
        row.put("tableAlias", safe(m.getTableAlias()));
        row.put("dataSourceId", m.getDataSourceId());
        row.put("schema", safe(m.getSchemaName()));
        row.put("datasetId", m.getDatasetId());
        row.put("keyColumns", safe(m.getKeyColumns()));
        row.put("joinToRole", safe(m.getJoinToRole()));
        row.put("includeInSubset", m.isIncludeInSubset());
        row.put("includeInSynthetic", m.isIncludeInSynthetic());
        return row;
    }

    private Long latestApprovedRequest(Long entityId, String operation) {
        List<Map<String, Object>> rows = rows("""
                SELECT id AS "id" FROM be_governance_requests
                 WHERE entity_id = ? AND status = 'APPROVED'
                   AND (action = ? OR action = 'RELEASE' OR action = 'RUN')
                 ORDER BY signed_at DESC, id DESC
                """, entityId, operation);
        if (rows.isEmpty()) return null;
        return ((Number) rows.get(0).get("id")).longValue();
    }

    private void upsertCatalog(Long entityId, String type, Long assetId, String qualifiedName, String displayName,
                               String owner, String domain, String tags, String certification,
                               Object lineage, Object dependencies, double quality) {
        List<Map<String, Object>> existing = rows("SELECT id FROM be_catalog_assets WHERE qualified_name = ?", qualifiedName);
        if (existing.isEmpty()) {
            insert("""
                    INSERT INTO be_catalog_assets(entity_id, asset_type, asset_id, qualified_name, display_name,
                        owner_username, domain, tags, certification_status, lineage_json, dependencies_json,
                        quality_score, status, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """, entityId, type, assetId, qualifiedName, displayName, blank(owner), blank(domain), blank(tags),
                    certification, writeJson(lineage), writeJson(dependencies), quality, ts(Instant.now()));
        } else {
            jdbc.update("""
                    UPDATE be_catalog_assets SET display_name = ?, owner_username = ?, domain = ?, tags = ?,
                        certification_status = ?, lineage_json = ?, dependencies_json = ?, quality_score = ?,
                        status = 'ACTIVE', updated_at = ? WHERE qualified_name = ?
                    """, displayName, blank(owner), blank(domain), blank(tags), certification, writeJson(lineage),
                    writeJson(dependencies), quality, ts(Instant.now()), qualifiedName);
        }
    }

    private void deleteStaleCatalogAssets(Long entityId, List<String> currentQualifiedNames) {
        if (currentQualifiedNames.isEmpty()) {
            jdbc.update("DELETE FROM be_catalog_assets WHERE entity_id = ?", entityId);
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(currentQualifiedNames.size(), "?"));
        List<Object> params = new ArrayList<>(currentQualifiedNames.size() + 1);
        params.add(entityId);
        params.addAll(currentQualifiedNames);
        jdbc.update("DELETE FROM be_catalog_assets WHERE entity_id = ? AND qualified_name NOT IN (" + placeholders + ")",
                params.toArray());
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(safe(value).toLowerCase(Locale.ROOT), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void updateDecision(Long id, String status, String signedBy, String comments, String signature) {
        String hash = sha256(id + "|" + status + "|" + signedBy + "|" + blank(comments) + "|" + blank(signature));
        jdbc.update("""
                UPDATE be_governance_requests SET status = ?, signed_by = ?, signed_at = ?, e_signature_hash = ?,
                    comments = ?, updated_at = ? WHERE id = ?
                """, status, signedBy, ts(Instant.now()), hash, blank(comments), ts(Instant.now()), id);
    }

    private Map<String, Object> getIssuePackage(Long id) {
        return one("""
                SELECT id, issue_key AS "issueKey", title, severity, status, approval_status AS "approvalStatus",
                       package_manifest_json AS "packageManifestJson", replay_instructions AS "replayInstructions"
                  FROM be_issue_packages WHERE id = ?
                """, id);
    }

    private Map<String, Object> getLookalikeProfile(Long id) {
        return one("""
                SELECT id, name, objective, privacy_mode AS "privacyMode", row_goal AS "rowGoal",
                       generator_plan_json AS "generatorPlanJson", safety_report_json AS "safetyReportJson", status
                  FROM be_lookalike_profiles WHERE id = ?
                """, id);
    }

    private Map<String, Object> getGovernanceRequest(Long id) {
        return one("""
                SELECT id, entity_id AS "entityId", object_type AS "objectType", object_id AS "objectId",
                       action, requested_by AS "requestedBy", reviewer, status, risk_level AS "riskLevel",
                       comments, signed_by AS "signedBy", signed_at AS "signedAt", e_signature_hash AS "eSignatureHash"
                  FROM be_governance_requests WHERE id = ?
                """, id);
    }

    private Map<String, Object> getOperationalPackageRaw(Long id) {
        return one("""
                SELECT id, entity_id AS "entityId", execution_plan_id AS "executionPlanId", package_type AS "packageType",
                       name, status, manifest_json AS "manifestJson", shell_script AS "shellScript",
                       health_check_json AS "healthCheckJson", promotion_json AS "promotionJson",
                       created_by AS "createdBy", updated_at AS "updatedAt"
                  FROM be_operational_packages WHERE id = ?
                """, id);
    }

    private Map<String, Object> getPackageVersion(Long id) {
        return one("""
                SELECT id, package_id AS "packageId", entity_id AS "entityId", version_number AS "versionNumber",
                       status, artifact_hash AS "artifactHash", manifest_json AS "manifestJson",
                       shell_script AS "shellScript", health_check_json AS "healthCheckJson",
                       promotion_json AS "promotionJson", immutable_manifest_json AS "immutableManifestJson",
                       retention_policy AS "retentionPolicy", retention_until AS "retentionUntil",
                       created_by AS "createdBy", created_at AS "createdAt"
                  FROM be_operational_package_versions WHERE id = ?
                """, id);
    }

    private Map<String, Object> getPackagePromotion(Long id) {
        return one("""
                SELECT id, package_id AS "packageId", entity_id AS "entityId", version_id AS "versionId",
                       from_environment AS "fromEnvironment", to_environment AS "toEnvironment", status,
                       approved_request_id AS "approvedRequestId", evidence_json AS "evidenceJson",
                       requested_by AS "requestedBy", approved_by AS "approvedBy", promoted_at AS "promotedAt",
                       created_at AS "createdAt", updated_at AS "updatedAt"
                  FROM be_operational_package_promotions WHERE id = ?
                """, id);
    }

    private Long latestPackageVersionId(Long packageId) {
        List<Map<String, Object>> rows = rows("""
                SELECT id FROM be_operational_package_versions
                 WHERE package_id = ? ORDER BY version_number DESC LIMIT 1
                """, packageId);
        return rows.isEmpty() ? null : num(rows.get(0).get("id"));
    }

    private Map<String, Object> getExecutionPlan(Long id) {
        return one("""
                SELECT id, entity_id AS "entityId", name, operation_type AS "operationType", mode, status,
                       issue_package_id AS "issuePackageId", lookalike_profile_id AS "lookalikeProfileId",
                       approved_request_id AS "approvedRequestId", plan_json AS "planJson",
                       validation_json AS "validationJson", loader_strategy_json AS "loaderStrategyJson"
                  FROM be_entity_execution_plans WHERE id = ?
                """, id);
    }

    private String runnerScript(Long packageId, Long entityId, Long planId, String name) {
        return """
                # ForgeTDM enterprise runner: %s
                param(
                  [string]$BaseUrl = $env:FORGETDM_BASE_URL,
                  [string]$Token = $env:FORGETDM_TOKEN,
                  [string]$TargetDataSourceId = $env:FORGETDM_TARGET_DATASOURCE_ID,
                  [string]$TargetSchema = $env:FORGETDM_TARGET_SCHEMA,
                  [string]$Seed = $env:FORGETDM_RUN_SEED
                )
                if (-not $BaseUrl) { throw "FORGETDM_BASE_URL is required" }
                if (-not $Token) { throw "FORGETDM_TOKEN is required" }
                $headers = @{ Authorization = "Bearer $Token"; "Content-Type" = "application/json" }
                Write-Host "Checking ForgeTDM API..."
                Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/auth/me" -Headers $headers | Out-Null
                Write-Host "Loading operational package %d for Business Entity %d..."
                $package = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/business-entities/operational-packages/%d" -Headers $headers
                Write-Host "Execution plan: %d"
                Write-Host "Package status: $($package.status)"
                $payload = @{
                  targetDataSourceId = if ($TargetDataSourceId) { [long]$TargetDataSourceId } else { $null }
                  targetSchema = if ($TargetSchema) { $TargetSchema } else { $null }
                  seed = if ($Seed) { [long]$Seed } else { $null }
                  maskingSeed = if ($Seed) { $Seed } else { $null }
                  loadAction = "REPLACE"
                  targetPrep = "DELETE"
                } | ConvertTo-Json -Depth 8
                $run = Invoke-RestMethod -Method POST -Uri "$BaseUrl/api/business-entities/execution-plans/%d/launch" -Headers $headers -Body $payload
                Write-Host "Submitted $($run.engine) run: $($run.runId) status=$($run.status)"
                """.formatted(name.replace("%", "%%"), packageId, entityId, packageId, planId, planId);
    }

    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, key);
        Number n = key.getKeyList().isEmpty() ? null : (Number) key.getKeyList().get(0).get("id");
        if (n == null) n = key.getKey();
        if (n == null) throw ApiException.bad("Insert did not return an id");
        return n.longValue();
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = rows(sql, args);
        if (rows.isEmpty()) throw ApiException.notFound("Enterprise object not found");
        return rows.get(0);
    }

    private String writeJson(Object v) {
        try { return json.writeValueAsString(v); }
        catch (Exception e) { throw ApiException.bad("Could not write enterprise manifest: " + e.getMessage()); }
    }

    private String governanceDecisionMetadata(Map<String, Object> request, String decisionStatus, DecisionRequest decision) {
        return auditMetadata(
                "entityId", request.get("entityId"),
                "objectType", request.get("objectType"),
                "objectId", request.get("objectId"),
                "governanceAction", request.get("action"),
                "decision", decisionStatus,
                "priorStatus", request.get("status"),
                "requestedBy", request.get("requestedBy"),
                "riskLevel", request.get("riskLevel"),
                "commentsPresent", blank(decision == null ? null : decision.comments()) != null,
                "eSignaturePresent", blank(decision == null ? null : decision.eSignature()) != null);
    }

    private String auditMetadata(Object... entries) {
        if (entries == null || entries.length % 2 != 0) {
            throw ApiException.bad("Audit metadata requires key/value pairs");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            Object value = entries[i + 1];
            if (value != null) metadata.put(String.valueOf(entries[i]), value);
        }
        return writeJson(metadata);
    }

    private JsonNode readJson(Object v, String label) {
        try {
            if (v == null) return json.createObjectNode();
            return json.readTree(String.valueOf(v));
        } catch (Exception e) {
            throw ApiException.bad("Could not read " + label + ": " + e.getMessage());
        }
    }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) return null;
        for (T v : values) if (v != null) return v;
        return null;
    }
    private static Long num(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (Exception e) { return null; }
    }
    private static long stableSeed(String name, Long id) {
        long value = Objects.hash(safe(name), id, "lookalike");
        return value == Long.MIN_VALUE ? 1L : Math.abs(value);
    }
    private static String text(JsonNode n, String field, String def) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) return def;
        return v.asText(def);
    }
    private static String required(String v, String label) {
        String clean = blank(v);
        if (clean == null) throw ApiException.bad(label + " is required");
        return clean;
    }
    private static String upperDefault(String v, String d) {
        String clean = blank(v);
        return (clean == null ? d : clean).toUpperCase(Locale.ROOT);
    }
    private static String blankToDefault(String v, String d) {
        String clean = blank(v);
        return clean == null ? d : clean;
    }
    private static String blank(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
    private static String safe(String v) {
        String clean = blank(v);
        return clean == null ? "" : clean;
    }
    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
    private static String currentUsername() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
    private static String authenticatedDecisionActor(DecisionRequest decision) {
        String actor = currentUsername();
        String suppliedReviewer = blank(decision == null ? null : decision.reviewer());
        if (suppliedReviewer != null && !suppliedReviewer.equalsIgnoreCase(actor)) {
            throw ApiException.bad("Reviewer must match the authenticated user.");
        }
        return actor;
    }
    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { return UUID.randomUUID().toString(); }
    }
}
