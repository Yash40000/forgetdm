package io.forgetdm.validation;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "validation_reports")
public class ValidationReportEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "job_id") private Long jobId;
    @Column(name = "data_source_id") private Long dataSourceId;
    @Column(name = "policy_id") private Long policyId;
    @Column(nullable = false) private String result;   // PASS | WARN | FAIL
    @Column(name = "findings_json", nullable = false, columnDefinition = "text") private String findingsJson;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    // Tenancy (V68 / RBAC-002). Reports are governed evidence, not global diagnostics.
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(name = "visibility") private String visibility;

    public Long getId() { return id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long v) { jobId = v; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { dataSourceId = v; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long v) { policyId = v; }
    public String getResult() { return result; }
    public void setResult(String v) { result = v; }
    public String getFindingsJson() { return findingsJson; }
    public void setFindingsJson(String v) { findingsJson = v; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { ownerUserId = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long v) { ownerGroupId = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { visibility = v; }
    public Instant getCreatedAt() { return createdAt; }
}
