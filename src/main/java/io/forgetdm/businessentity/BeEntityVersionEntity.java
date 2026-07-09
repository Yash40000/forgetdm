package io.forgetdm.businessentity;

import jakarta.persistence.*;
import java.time.Instant;

/** Version history entry for an entity instance capsule — one row per materialize/refresh. */
@Entity
@Table(name = "be_entity_versions")
public class BeEntityVersionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "instance_id", nullable = false) private Long instanceId;
    @Column(name = "version_no", nullable = false) private int versionNo;
    @Column(nullable = false) private String kind = "RAW_REF_ONLY";
    @Column(name = "policy_id") private Long policyId;
    @Column(name = "fragment_count", nullable = false) private int fragmentCount;
    @Column(name = "total_rows", nullable = false) private long totalRows;
    @Column(name = "content_hash") private String contentHash;
    @Column(columnDefinition = "text") private String notes;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long v) { instanceId = v; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int v) { versionNo = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { kind = v; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long v) { policyId = v; }
    public int getFragmentCount() { return fragmentCount; }
    public void setFragmentCount(int v) { fragmentCount = v; }
    public long getTotalRows() { return totalRows; }
    public void setTotalRows(long v) { totalRows = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { contentHash = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { notes = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
}
