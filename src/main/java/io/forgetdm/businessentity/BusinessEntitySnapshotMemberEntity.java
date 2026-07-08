package io.forgetdm.businessentity;

import jakarta.persistence.*;

@Entity
@Table(name = "business_entity_snapshot_members")
public class BusinessEntitySnapshotMemberEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "snapshot_id", nullable = false) private Long snapshotId;
    @Column(name = "entity_member_id") private Long entityMemberId;
    @Column(name = "data_source_id") private Long dataSourceId;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "table_name", nullable = false) private String tableName;
    @Column(name = "key_columns", columnDefinition = "text") private String keyColumns;
    @Column(name = "row_keys_json", columnDefinition = "text") private String rowKeysJson;
    @Column(columnDefinition = "text") private String criteria;
    @Column(name = "virtual_snapshot_id") private Long virtualSnapshotId;
    @Column(name = "row_count", nullable = false) private long rowCount;
    @Column(nullable = false) private String status = "CAPTURED";
    @Column(name = "evidence_json", columnDefinition = "text") private String evidenceJson;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long v) { snapshotId = v; }
    public Long getEntityMemberId() { return entityMemberId; }
    public void setEntityMemberId(Long v) { entityMemberId = v; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { dataSourceId = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { schemaName = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { tableName = v; }
    public String getKeyColumns() { return keyColumns; }
    public void setKeyColumns(String v) { keyColumns = v; }
    public String getRowKeysJson() { return rowKeysJson; }
    public void setRowKeysJson(String v) { rowKeysJson = v; }
    public String getCriteria() { return criteria; }
    public void setCriteria(String v) { criteria = v; }
    public Long getVirtualSnapshotId() { return virtualSnapshotId; }
    public void setVirtualSnapshotId(Long v) { virtualSnapshotId = v; }
    public long getRowCount() { return rowCount; }
    public void setRowCount(long v) { rowCount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String v) { evidenceJson = v; }
}
