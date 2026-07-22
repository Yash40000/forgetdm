package io.forgetdm.unstructured;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "unstructured_masking_profiles")
public class UnstructuredProfileEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "rules_json", columnDefinition = "text", nullable = false) private String rulesJson;
    @Column(nullable = false) private String status = "DRAFT";
    @Column(name = "version_no", nullable = false) private int versionNo = 1;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    // Tenancy (V68 / RBAC-002). Legacy rows are migrated to SHARED.
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(name = "visibility") private String visibility;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String v) { rulesJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int v) { versionNo = v; }
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
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
