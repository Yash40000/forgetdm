package io.forgetdm.provision;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "provision_jobs")
public class ProvisionJobEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String name;
    @Column(name = "job_type", nullable = false) private String jobType;  // MASK_COPY | SUBSET_MASK | SYNTHETIC_LOAD
    @Column(name = "source_id") private Long sourceId;
    @Column(name = "target_id") private Long targetId;
    @Column(name = "policy_id") private Long policyId;
    @Column(name = "dataset_id") private Long datasetId;
    @Column(name = "spec_json", columnDefinition = "text") private String specJson;
    @Column(nullable = false) private String status = "PENDING";
    @Column(name = "rows_processed") private long rowsProcessed;
    @Column(columnDefinition = "text") private String message;
    @Column(name = "table_states_json", columnDefinition = "text") private String tableStatesJson;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    // Maker-checker approval (V29): NOT_REQUIRED | PENDING_APPROVAL | APPROVED | REJECTED
    @Column(name = "created_by") private String createdBy;
    // Tenancy (V65 / RBAC-002). visibility: PRIVATE | GROUP | SHARED; legacy rows are SHARED.
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(name = "visibility") private String visibility = "GROUP";
    @Column(name = "approval_status", nullable = false) private String approvalStatus = "NOT_REQUIRED";
    @Column(name = "approval_requested_at") private Instant approvalRequestedAt;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approval_note", columnDefinition = "text") private String approvalNote;
    // Constraint-failure diagnostic (JSON): failed record original-vs-masked + the conflicting record.
    @Column(name = "conflict_json", columnDefinition = "text") private String conflictJson;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getJobType() { return jobType; }
    public void setJobType(String v) { jobType = v; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long v) { sourceId = v; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long v) { targetId = v; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long v) { policyId = v; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long v) { datasetId = v; }
    public String getSpecJson() { return specJson; }
    public void setSpecJson(String v) { specJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public long getRowsProcessed() { return rowsProcessed; }
    public void setRowsProcessed(long v) { rowsProcessed = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public String getTableStatesJson() { return tableStatesJson; }
    public void setTableStatesJson(String v) { tableStatesJson = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { finishedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { ownerUserId = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long v) { ownerGroupId = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { visibility = v; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String v) { approvalStatus = v; }
    public Instant getApprovalRequestedAt() { return approvalRequestedAt; }
    public void setApprovalRequestedAt(Instant v) { approvalRequestedAt = v; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant v) { approvedAt = v; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String v) { approvedBy = v; }
    public String getApprovalNote() { return approvalNote; }
    public void setApprovalNote(String v) { approvalNote = v; }
    public String getConflictJson() { return conflictJson; }
    public void setConflictJson(String v) { conflictJson = v; }
}
