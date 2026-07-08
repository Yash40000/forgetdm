package io.forgetdm.businessentity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "business_entities")
public class BusinessEntityDefinitionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(columnDefinition = "text") private String description;
    private String domain;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "primary_dataset_id") private Long primaryDatasetId;
    @Column(name = "root_table") private String rootTable;
    @Column(name = "business_key_columns", columnDefinition = "text") private String businessKeyColumns;
    @Column(nullable = false) private String status = "ACTIVE";
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getDomain() { return domain; }
    public void setDomain(String v) { domain = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public Long getPrimaryDatasetId() { return primaryDatasetId; }
    public void setPrimaryDatasetId(Long v) { primaryDatasetId = v; }
    public String getRootTable() { return rootTable; }
    public void setRootTable(String v) { rootTable = v; }
    public String getBusinessKeyColumns() { return businessKeyColumns; }
    public void setBusinessKeyColumns(String v) { businessKeyColumns = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
