package io.forgetdm.businessentity;

import jakarta.persistence.*;

@Entity
@Table(name = "business_entity_members")
public class BusinessEntityMemberEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "entity_id", nullable = false) private Long entityId;
    @Column(name = "system_name") private String systemName;
    @Column(name = "data_source_id") private Long dataSourceId;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "dataset_id") private Long datasetId;
    @Column(name = "logical_role", nullable = false) private String logicalRole;
    @Column(name = "table_name", nullable = false) private String tableName;
    @Column(name = "table_alias") private String tableAlias;
    @Column(name = "key_columns", columnDefinition = "text") private String keyColumns;
    @Column(name = "join_to_role") private String joinToRole;
    @Column(name = "relationship_json", columnDefinition = "text") private String relationshipJson;
    @Column(name = "include_in_subset", nullable = false) private boolean includeInSubset = true;
    @Column(name = "include_in_synthetic", nullable = false) private boolean includeInSynthetic = true;
    @Column(name = "ordinal_no", nullable = false) private int ordinalNo = 0;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getEntityId() { return entityId; }
    public void setEntityId(Long v) { entityId = v; }
    public String getSystemName() { return systemName; }
    public void setSystemName(String v) { systemName = v; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { dataSourceId = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { schemaName = v; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long v) { datasetId = v; }
    public String getLogicalRole() { return logicalRole; }
    public void setLogicalRole(String v) { logicalRole = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { tableName = v; }
    public String getTableAlias() { return tableAlias; }
    public void setTableAlias(String v) { tableAlias = v; }
    public String getKeyColumns() { return keyColumns; }
    public void setKeyColumns(String v) { keyColumns = v; }
    public String getJoinToRole() { return joinToRole; }
    public void setJoinToRole(String v) { joinToRole = v; }
    public String getRelationshipJson() { return relationshipJson; }
    public void setRelationshipJson(String v) { relationshipJson = v; }
    public boolean isIncludeInSubset() { return includeInSubset; }
    public void setIncludeInSubset(boolean v) { includeInSubset = v; }
    public boolean isIncludeInSynthetic() { return includeInSynthetic; }
    public void setIncludeInSynthetic(boolean v) { includeInSynthetic = v; }
    public int getOrdinalNo() { return ordinalNo; }
    public void setOrdinalNo(int v) { ordinalNo = v; }
}
