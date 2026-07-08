package io.forgetdm.businessentity;

import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/business-entities")
public class BusinessEntityController {
    private final BusinessEntityService svc;
    private final BusinessEntitySnapshotService snapshots;
    private final BusinessEntityReservationService reservations;
    private final BusinessEntityEnterpriseService enterprise;

    public BusinessEntityController(BusinessEntityService svc,
                                    BusinessEntitySnapshotService snapshots,
                                    BusinessEntityReservationService reservations,
                                    BusinessEntityEnterpriseService enterprise) {
        this.svc = svc;
        this.snapshots = snapshots;
        this.reservations = reservations;
        this.enterprise = enterprise;
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
}
