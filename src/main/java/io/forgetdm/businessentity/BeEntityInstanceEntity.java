package io.forgetdm.businessentity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A K2View-style "entity instance" — one governed, reusable capsule for a single business
 * entity occurrence (for example Customer 360 / CUST-10025), identified by its canonical
 * business key. Shared physical table across all instances of all Business Entities;
 * NOT a database per customer/account.
 */
@Entity
@Table(name = "be_entity_instances")
public class BeEntityInstanceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "entity_id", nullable = false) private Long entityId;
    @Column(name = "canonical_key", nullable = false) private String canonicalKey;
    @Column(name = "business_key_json", nullable = false, columnDefinition = "text") private String businessKeyJson;
    @Column(nullable = false) private String status = "ACTIVE";
    @Column(name = "policy_id") private Long policyId;
    @Column(name = "current_version", nullable = false) private int currentVersion;
    @Column(name = "fragment_count", nullable = false) private int fragmentCount;
    @Column(name = "total_rows", nullable = false) private long totalRows;
    @Column(name = "last_materialized_at") private Instant lastMaterializedAt;
    @Column(name = "last_materialized_by") private String lastMaterializedBy;
    @Column(columnDefinition = "text") private String notes;
    /** MANUAL (default) or ON_DEMAND: refresh automatically at access time once stale. */
    @Column(name = "sync_mode", nullable = false) private String syncMode = "MANUAL";
    /** Staleness budget for ON_DEMAND sync; null = never considered stale. */
    @Column(name = "stale_after_minutes") private Integer staleAfterMinutes;
    /** Random per-instance salt; the fragment encryption key is derived from (master secret, salt). */
    @Column(name = "key_salt") private String keySalt;
    @Column(name = "retired_at") private Instant retiredAt;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long v) { entityId = v; }
    public String getCanonicalKey() { return canonicalKey; }
    public void setCanonicalKey(String v) { canonicalKey = v; }
    public String getBusinessKeyJson() { return businessKeyJson; }
    public void setBusinessKeyJson(String v) { businessKeyJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long v) { policyId = v; }
    public int getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(int v) { currentVersion = v; }
    public int getFragmentCount() { return fragmentCount; }
    public void setFragmentCount(int v) { fragmentCount = v; }
    public long getTotalRows() { return totalRows; }
    public void setTotalRows(long v) { totalRows = v; }
    public Instant getLastMaterializedAt() { return lastMaterializedAt; }
    public void setLastMaterializedAt(Instant v) { lastMaterializedAt = v; }
    public String getLastMaterializedBy() { return lastMaterializedBy; }
    public void setLastMaterializedBy(String v) { lastMaterializedBy = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { notes = v; }
    public String getSyncMode() { return syncMode; }
    public void setSyncMode(String v) { syncMode = v; }
    public Integer getStaleAfterMinutes() { return staleAfterMinutes; }
    public void setStaleAfterMinutes(Integer v) { staleAfterMinutes = v; }
    public String getKeySalt() { return keySalt; }
    public void setKeySalt(String v) { keySalt = v; }
    public Instant getRetiredAt() { return retiredAt; }
    public void setRetiredAt(Instant v) { retiredAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
