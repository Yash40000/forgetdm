package io.forgetdm.dataset;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * An Access Definition: describes WHAT data to extract from a source, HOW to traverse
 * the FK graph, and WHICH tables/columns to include/exclude or override.
 * Stored in the DB, not in files.
 */
@Entity
@Table(name = "dataset_definitions")
public class DataSetDefinitionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true)   private String name;
    @Column(columnDefinition = "text")         private String description;
    @Column(name = "data_source_id", nullable = false) private Long dataSourceId;
    @Column(name = "schema_name")              private String schemaName;
    @Column(name = "target_data_source_id")    private Long targetDataSourceId;
    @Column(name = "target_schema_name")       private String targetSchemaName;
    @Column(name = "policy_id")                private Long policyId;
    @Column(name = "driver_table")             private String driverTable;
    @Column(name = "driver_filter", columnDefinition = "text") private String driverFilter;
    /** Global default: include parent rows (Q1). Each table can override. */
    @Column(name = "global_q1", nullable = false) private boolean globalQ1 = true;
    /** Global default: include child rows (Q2). Each table can override. */
    @Column(name = "global_q2", nullable = false) private boolean globalQ2 = true;
    @Column(name = "created_at")               private Instant createdAt = Instant.now();
    @Column(name = "updated_at")               private Instant updatedAt = Instant.now();

    // Tenancy (V61 / DEF-0007). visibility: PRIVATE | GROUP | SHARED — legacy rows are SHARED.
    @Column(name = "owner_user_id")  private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(name = "visibility")     private String visibility = "GROUP";

    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { ownerUserId = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long v) { ownerGroupId = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { visibility = v; }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { dataSourceId = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { schemaName = v; }
    public Long getTargetDataSourceId() { return targetDataSourceId; }
    public void setTargetDataSourceId(Long v) { targetDataSourceId = v; }
    public String getTargetSchemaName() { return targetSchemaName; }
    public void setTargetSchemaName(String v) { targetSchemaName = v; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long v) { policyId = v; }
    public String getDriverTable() { return driverTable; }
    public void setDriverTable(String v) { driverTable = v; }
    public String getDriverFilter() { return driverFilter; }
    public void setDriverFilter(String v) { driverFilter = v; }
    public boolean isGlobalQ1() { return globalQ1; }
    public void setGlobalQ1(boolean v) { globalQ1 = v; }
    public boolean isGlobalQ2() { return globalQ2; }
    public void setGlobalQ2(boolean v) { globalQ2 = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
