package io.forgetdm.businessentity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "business_entity_reservations")
public class BusinessEntityReservationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "entity_id", nullable = false) private Long entityId;
    @Column(name = "snapshot_id") private Long snapshotId;
    private String name;
    @Column(name = "reserved_by", nullable = false) private String reservedBy;
    @Column(name = "owner_group") private String ownerGroup;
    @Column(columnDefinition = "text") private String purpose;
    private String environment;
    @Column(columnDefinition = "text") private String criteria;
    @Column(name = "requested_count", nullable = false) private int requestedCount = 1;
    @Column(name = "business_key_values_json", nullable = false, columnDefinition = "text") private String businessKeyValuesJson;
    @Column(nullable = false) private String status = "ACTIVE";
    @Column(name = "conflict_policy", nullable = false) private String conflictPolicy = "BLOCK";
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "released_at") private Instant releasedAt;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long v) { entityId = v; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long v) { snapshotId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getReservedBy() { return reservedBy; }
    public void setReservedBy(String v) { reservedBy = v; }
    public String getOwnerGroup() { return ownerGroup; }
    public void setOwnerGroup(String v) { ownerGroup = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { purpose = v; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String v) { environment = v; }
    public String getCriteria() { return criteria; }
    public void setCriteria(String v) { criteria = v; }
    public int getRequestedCount() { return requestedCount; }
    public void setRequestedCount(int v) { requestedCount = v; }
    public String getBusinessKeyValuesJson() { return businessKeyValuesJson; }
    public void setBusinessKeyValuesJson(String v) { businessKeyValuesJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getConflictPolicy() { return conflictPolicy; }
    public void setConflictPolicy(String v) { conflictPolicy = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { expiresAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant v) { releasedAt = v; }
}
