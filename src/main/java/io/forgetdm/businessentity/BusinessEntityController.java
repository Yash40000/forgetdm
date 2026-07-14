package io.forgetdm.businessentity;

import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/business-entities")
public class BusinessEntityController {
    private final BusinessEntityService svc;
    private final BusinessEntitySnapshotService snapshots;
    private final BusinessEntityReservationService reservations;
    private final BusinessEntityEnterpriseService enterprise;
    private final BusinessEntityFlowService flows;
    private final BusinessEntityIdentityService identities;
    private final BusinessEntitySyncService sync;
    private final BusinessEntityCapsuleService capsules;

    public BusinessEntityController(BusinessEntityService svc,
                                    BusinessEntitySnapshotService snapshots,
                                    BusinessEntityReservationService reservations,
                                    BusinessEntityEnterpriseService enterprise,
                                    BusinessEntityFlowService flows,
                                    BusinessEntityIdentityService identities,
                                    BusinessEntitySyncService sync,
                                    BusinessEntityCapsuleService capsules) {
        this.svc = svc;
        this.snapshots = snapshots;
        this.reservations = reservations;
        this.enterprise = enterprise;
        this.flows = flows;
        this.identities = identities;
        this.sync = sync;
        this.capsules = capsules;
    }

    @GetMapping
    public List<BusinessEntityService.BusinessEntitySummary> list() {
        return svc.list();
    }

    @GetMapping("/{id}")
    public BusinessEntityService.BusinessEntityDetail get(@PathVariable Long id) {
        return svc.getDetail(id);
    }

    @PostMapping
    public BusinessEntityDefinitionEntity create(@RequestBody BusinessEntityDefinitionEntity body) {
        return svc.create(body);
    }

    @PostMapping("/from-dataset/{datasetId}")
    public BusinessEntityService.BusinessEntityDetail createFromDataset(
            @PathVariable Long datasetId,
            @RequestBody(required = false) BusinessEntityService.FromDatasetRequest body) {
        return svc.createFromDataset(datasetId, body);
    }

    @PostMapping("/{id}/datasets/{datasetId}/import")
    public BusinessEntityService.DatasetImportResult importDataset(
            @PathVariable Long id,
            @PathVariable Long datasetId,
            @RequestBody(required = false) BusinessEntityService.ImportDatasetRequest body) {
        return svc.importDataset(id, datasetId, body);
    }

    @PutMapping("/{id}")
    public BusinessEntityDefinitionEntity update(@PathVariable Long id,
                                                 @RequestBody BusinessEntityDefinitionEntity body) {
        return svc.update(id, body);
    }

    @PutMapping("/{id}/members")
    public List<BusinessEntityMemberEntity> replaceMembers(@PathVariable Long id,
                                                           @RequestBody List<BusinessEntityMemberEntity> body) {
        return svc.replaceMembers(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id) {
        svc.delete(id);
    }

    @GetMapping("/{id}/snapshots")
    public List<BusinessEntitySnapshotEntity> listSnapshots(@PathVariable Long id) {
        return snapshots.list(id);
    }

    @PostMapping("/{id}/snapshots")
    public BusinessEntitySnapshotService.SnapshotDetail createSnapshot(
            @PathVariable Long id,
            @RequestBody(required = false) BusinessEntitySnapshotService.SnapshotRequest body) {
        return snapshots.create(id, body);
    }

    @GetMapping("/snapshots/{snapshotId}")
    public BusinessEntitySnapshotService.SnapshotDetail snapshotDetail(@PathVariable Long snapshotId) {
        return snapshots.detail(snapshotId);
    }

    @PostMapping("/snapshots/{snapshotId}/rollback")
    public java.util.Map<String, Object> rollbackSnapshot(
            @PathVariable Long snapshotId,
            @RequestBody(required = false) BusinessEntitySnapshotService.RollbackRequest body) {
        return snapshots.rollback(snapshotId, body);
    }

    @GetMapping("/{id}/reservations")
    public List<BusinessEntityReservationEntity> listReservations(@PathVariable Long id) {
        return reservations.list(id);
    }

    @PostMapping("/{id}/reservations")
    public BusinessEntityReservationService.ReservationDetail reserve(
            @PathVariable Long id,
            @RequestBody(required = false) BusinessEntityReservationService.ReservationRequest body) {
        return reservations.reserve(id, body);
    }

    @GetMapping("/reservations/{reservationId}")
    public BusinessEntityReservationService.ReservationDetail reservationDetail(@PathVariable Long reservationId) {
        return reservations.detail(reservationId);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public BusinessEntityReservationService.ReservationDetail releaseReservation(@PathVariable Long reservationId) {
        return reservations.release(reservationId);
    }

    @GetMapping("/{id}/enterprise")
    public java.util.Map<String, Object> enterpriseDashboard(@PathVariable Long id) {
        return enterprise.dashboard(id);
    }

    @GetMapping("/{id}/identities")
    public java.util.List<java.util.Map<String, Object>> listIdentities(
            @PathVariable Long id,
            @RequestParam(name = "q", required = false) String q) {
        return identities.list(id, q);
    }

    @PostMapping("/{id}/identities")
    public java.util.Map<String, Object> upsertIdentity(
            @PathVariable Long id,
            @RequestBody BusinessEntityIdentityService.CrosswalkRequest body) {
        return identities.upsert(id, body);
    }

    @GetMapping("/identities/{subjectId}")
    public java.util.Map<String, Object> getIdentity(@PathVariable Long subjectId) {
        return identities.get(subjectId);
    }

    @PostMapping("/{id}/identities/resolve")
    public java.util.Map<String, Object> resolveIdentity(
            @PathVariable Long id,
            @RequestBody BusinessEntityIdentityService.ResolveRequest body) {
        return identities.resolve(id, body);
    }

    @PostMapping("/{id}/identities/{subjectId}/links")
    public java.util.Map<String, Object> addIdentityLink(
            @PathVariable Long id,
            @PathVariable Long subjectId,
            @RequestBody BusinessEntityIdentityService.LinkRequest body) {
        return identities.addLink(id, subjectId, body);
    }

    @DeleteMapping("/identities/{subjectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIdentity(@PathVariable Long subjectId) {
        identities.deleteSubject(subjectId);
    }

    @DeleteMapping("/identity-links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIdentityLink(@PathVariable Long linkId) {
        identities.deleteLink(linkId);
    }

    @GetMapping("/{id}/sync-policies")
    public java.util.List<java.util.Map<String, Object>> listSyncPolicies(@PathVariable Long id) {
        return sync.listPolicies(id);
    }

    @PostMapping("/{id}/sync-policies")
    public java.util.Map<String, Object> saveSyncPolicy(
            @PathVariable Long id,
            @RequestBody BusinessEntitySyncService.SyncPolicyRequest body) {
        return sync.savePolicy(id, body);
    }

    @GetMapping("/sync-policies/{policyId}")
    public java.util.Map<String, Object> getSyncPolicy(@PathVariable Long policyId) {
        return sync.getPolicy(policyId);
    }

    @DeleteMapping("/sync-policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSyncPolicy(@PathVariable Long policyId) {
        sync.deletePolicy(policyId);
    }

    @PostMapping("/sync-policies/{policyId}/check")
    public java.util.Map<String, Object> checkSyncPolicy(@PathVariable Long policyId) {
        return sync.checkFreshness(policyId);
    }

    @GetMapping("/sync-policies/{policyId}/runs")
    public java.util.List<java.util.Map<String, Object>> listSyncRuns(@PathVariable Long policyId) {
        return sync.listRuns(policyId);
    }

    @PostMapping("/sync-policies/{policyId}/heartbeat")
    public java.util.Map<String, Object> syncHeartbeat(
            @PathVariable Long policyId,
            @RequestBody BusinessEntitySyncService.HeartbeatRequest body) {
        return sync.heartbeat(policyId, body);
    }

    @GetMapping("/{id}/flows")
    public java.util.List<java.util.Map<String, Object>> listFlows(@PathVariable Long id) {
        return flows.listFlows(id);
    }

    @GetMapping("/{id}/flows/starter")
    public java.util.Map<String, Object> starterFlow(@PathVariable Long id) {
        return flows.starterFlow(id);
    }

    @PostMapping("/{id}/flows")
    public java.util.Map<String, Object> saveFlow(
            @PathVariable Long id,
            @RequestBody BusinessEntityFlowService.FlowRequest body) {
        return flows.saveFlow(id, body);
    }

    @GetMapping("/flows/{flowId}")
    public java.util.Map<String, Object> getFlow(@PathVariable Long flowId) {
        return flows.getFlow(flowId);
    }

    @DeleteMapping("/flows/{flowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFlow(@PathVariable Long flowId) {
        flows.deleteFlow(flowId);
    }

    @GetMapping("/flows/{flowId}/debug-runs")
    public java.util.List<java.util.Map<String, Object>> listDebugRuns(@PathVariable Long flowId) {
        return flows.listDebugRuns(flowId);
    }

    @PostMapping("/flows/{flowId}/validate")
    public java.util.Map<String, Object> validateFlow(@PathVariable Long flowId) {
        return flows.validateFlow(flowId);
    }

    @PostMapping("/flows/{flowId}/publish")
    public java.util.Map<String, Object> publishFlow(@PathVariable Long flowId) {
        return flows.publishFlow(flowId);
    }

    @PostMapping("/flows/{flowId}/debug")
    public java.util.Map<String, Object> debugFlow(
            @PathVariable Long flowId,
            @RequestBody(required = false) BusinessEntityFlowService.DebugRequest body) {
        return flows.debugFlow(flowId, body);
    }

    @PostMapping("/flows/{flowId}/run")
    public java.util.Map<String, Object> runFlow(
            @PathVariable Long flowId,
            @RequestBody(required = false) BusinessEntityFlowService.RunRequest body) {
        return flows.runFlow(flowId, body);
    }

    @PostMapping("/{id}/issue-packages")
    public java.util.Map<String, Object> createIssuePackage(
            @PathVariable Long id,
            @RequestBody BusinessEntityEnterpriseService.IssuePackageRequest body) {
        return enterprise.createIssuePackage(id, body);
    }

    @PostMapping("/{id}/lookalike-profiles")
    public java.util.Map<String, Object> createLookalikeProfile(
            @PathVariable Long id,
            @RequestBody BusinessEntityEnterpriseService.LookalikeProfileRequest body) {
        return enterprise.createLookalikeProfile(id, body);
    }

    @PostMapping("/{id}/catalog/sync")
    public java.util.Map<String, Object> syncCatalog(@PathVariable Long id) {
        return enterprise.syncCatalog(id);
    }

    @PostMapping("/{id}/governance-requests")
    public java.util.Map<String, Object> createGovernanceRequest(
            @PathVariable Long id,
            @RequestBody BusinessEntityEnterpriseService.GovernanceRequestRequest body) {
        return enterprise.createGovernanceRequest(id, body);
    }

    @PostMapping("/governance-requests/{requestId}/approve")
    public java.util.Map<String, Object> approveGovernanceRequest(
            @PathVariable Long requestId,
            @RequestBody(required = false) BusinessEntityEnterpriseService.DecisionRequest body) {
        return enterprise.approveGovernanceRequest(requestId, body);
    }

    @PostMapping("/governance-requests/{requestId}/reject")
    public java.util.Map<String, Object> rejectGovernanceRequest(
            @PathVariable Long requestId,
            @RequestBody(required = false) BusinessEntityEnterpriseService.DecisionRequest body) {
        return enterprise.rejectGovernanceRequest(requestId, body);
    }

    @PostMapping("/{id}/execution-plans")
    public java.util.Map<String, Object> createExecutionPlan(
            @PathVariable Long id,
            @RequestBody BusinessEntityEnterpriseService.ExecutionPlanRequest body) {
        return enterprise.createExecutionPlan(id, body);
    }

    @PostMapping("/execution-plans/{planId}/launch")
    public java.util.Map<String, Object> launchExecutionPlan(
            @PathVariable Long planId,
            @RequestBody(required = false) BusinessEntityEnterpriseService.LaunchRequest body) {
        return enterprise.launchExecutionPlan(planId, body);
    }

    @PostMapping("/{id}/operational-packages")
    public java.util.Map<String, Object> createOperationalPackage(
            @PathVariable Long id,
            @RequestBody BusinessEntityEnterpriseService.OperationalPackageRequest body) {
        return enterprise.createOperationalPackage(id, body);
    }

    @GetMapping("/operational-packages/{packageId}")
    public java.util.Map<String, Object> getOperationalPackage(@PathVariable Long packageId) {
        return enterprise.getOperationalPackage(packageId);
    }

    @GetMapping("/operational-packages/{packageId}/versions")
    public java.util.List<java.util.Map<String, Object>> listPackageVersions(@PathVariable Long packageId) {
        return enterprise.listPackageVersions(packageId);
    }

    @PostMapping("/operational-packages/{packageId}/versions")
    public java.util.Map<String, Object> createPackageVersion(
            @PathVariable Long packageId,
            @RequestBody(required = false) BusinessEntityEnterpriseService.PackageVersionRequest body) {
        return enterprise.createPackageVersion(packageId, body);
    }

    @PostMapping("/operational-packages/{packageId}/promotions")
    public java.util.Map<String, Object> promotePackageVersion(
            @PathVariable Long packageId,
            @RequestBody BusinessEntityEnterpriseService.PromotionRequest body) {
        return enterprise.promotePackageVersion(packageId, body);
    }

    // ---------- Micro-Database / Entity Capsules ----------

    @GetMapping("/{id}/capsules")
    public List<BeEntityInstanceEntity> listCapsules(@PathVariable Long id) {
        return capsules.list(id);
    }

    @PostMapping("/{id}/capsules/materialize")
    public BusinessEntityCapsuleService.CapsuleDetail materializeCapsule(
            @PathVariable Long id,
            @RequestBody BusinessEntityCapsuleService.MaterializeRequest body) {
        return capsules.materialize(id, body);
    }

    @GetMapping("/capsules/{instanceId}")
    public BusinessEntityCapsuleService.CapsuleDetail capsuleDetail(@PathVariable Long instanceId) {
        // Honors sync-on-demand: a stale ON_DEMAND capsule refreshes transparently at access time.
        return capsules.detailWithSync(instanceId);
    }

    @GetMapping("/capsules/{instanceId}/versions/{versionNo}/fragments")
    public List<BeEntityFragmentEntity> capsuleVersionFragments(@PathVariable Long instanceId,
                                                                @PathVariable int versionNo) {
        return capsules.versionFragments(instanceId, versionNo);
    }

    @PostMapping("/capsules/{instanceId}/versions/{versionNo}/restore")
    public BusinessEntityCapsuleService.CapsuleDetail restoreCapsuleVersion(@PathVariable Long instanceId,
                                                                            @PathVariable int versionNo) {
        return capsules.restoreVersion(instanceId, versionNo);
    }

    @PostMapping("/capsules/{instanceId}/provision")
    public Map<String, Object> provisionFromCapsule(
            @PathVariable Long instanceId,
            @RequestBody BusinessEntityCapsuleService.ProvisionFromCapsuleRequest body) {
        return capsules.provisionToTarget(instanceId, body);
    }

    @PostMapping("/capsules/{instanceId}/retire")
    public BusinessEntityCapsuleService.CapsuleDetail retireCapsule(@PathVariable Long instanceId) {
        return capsules.retire(instanceId);
    }

    @PostMapping("/capsules/{instanceId}/access-grants")
    public BusinessEntityCapsuleService.CapsuleDetail grantCapsuleAccess(
            @PathVariable Long instanceId,
            @RequestBody BusinessEntityCapsuleService.AccessGrantRequest body) {
        return capsules.grantAccess(instanceId, body);
    }

    @PostMapping("/capsule-access-grants/{grantId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeCapsuleAccess(@PathVariable Long grantId) {
        capsules.revokeAccess(grantId);
    }
}
