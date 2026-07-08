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
    public Instant getCreatedAt() { return createdAt; }
}
