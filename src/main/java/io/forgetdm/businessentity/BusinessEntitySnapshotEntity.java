package io.forgetdm.businessentity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "business_entity_snapshots")
public class BusinessEntitySnapshotEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "entity_id", nullable = false) private Long entityId;
    @Column(nullable = false) private String name;
    @Column(name = "snapshot_type", nullable = false) private String snapshotType = "ENTITY_BOOKMARK";
    @Column(name = "capture_mode", nullable = false) private String captureMode = "EVIDENCE_ONLY";
    @Column(nullable = false) private String status = "AVAILABLE";
    @Column(name = "consistency_id", nullable = false) private String consistencyId;
    @Column(name = "created_by") private String createdBy;
    @Column(columnDefinition = "text") private String note;
    @Column(name = "retention_until") private Instant retentionUntil;
    @Column(nullable = false) private boolean immutable = true;
    @Column(name = "entity_json", nullable = false, columnDefinition = "text") private String entityJson;
    @Column(name = "member_manifest_json", nullable = false, columnDefinition = "text") private String memberManifestJson;
    @Column(name = "rollback_plan_json", columnDefinition = "text") private String rollbackPlanJson;
    @Column(name = "total_members", nullable = false) private int totalMembers;
    @Column(name = "linked_virtual_snapshots", nullable = false) private int linkedVirtualSnapshots;
    @Column(name = "total_rows", nullable = false) private long totalRows;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "completed_at") private Instant completedAt;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long v) { entityId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String v) { snapshotType = v; }
    public String getCaptureMode() { return captureMode; }
    public void setCaptureMode(String v) { captureMode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getConsistencyId() { return consistencyId; }
    public void setConsistencyId(String v) { consistencyId = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public String getNote() { return note; }
    public void setNote(String v) { note = v; }
    public Instant getRetentionUntil() { return retentionUntil; }
    public void setRetentionUntil(Instant v) { retentionUntil = v; }
    public boolean isImmutable() { return immutable; }
    public void setImmutable(boolean v) { immutable = v; }
    public String getEntityJson() { return entityJson; }
    public void setEntityJson(String v) { entityJson = v; }
    public String getMemberManifestJson() { return memberManifestJson; }
    public void setMemberManifestJson(String v) { memberManifestJson = v; }
    public String getRollbackPlanJson() { return rollbackPlanJson; }
    public void setRollbackPlanJson(String v) { rollbackPlanJson = v; }
    public int getTotalMembers() { return totalMembers; }
    public void setTotalMembers(int v) { totalMembers = v; }
    public int getLinkedVirtualSnapshots() { return linkedVirtualSnapshots; }
    public void setLinkedVirtualSnapshots(int v) { linkedVirtualSnapshots = v; }
    public long getTotalRows() { return totalRows; }
    public void setTotalRows(long v) { totalRows = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { completedAt = v; }
}
