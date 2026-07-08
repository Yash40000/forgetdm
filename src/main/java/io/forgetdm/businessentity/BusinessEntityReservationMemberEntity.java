package io.forgetdm.businessentity;

import jakarta.persistence.*;

@Entity
@Table(name = "business_entity_reservation_members")
public class BusinessEntityReservationMemberEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "reservation_id", nullable = false) private Long reservationId;
    @Column(name = "entity_member_id") private Long entityMemberId;
    @Column(name = "data_source_id") private Long dataSourceId;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "table_name", nullable = false) private String tableName;
    @Column(name = "key_columns", columnDefinition = "text") private String keyColumns;
    @Column(name = "row_keys_json", nullable = false, columnDefinition = "text") private String rowKeysJson;
    @Column(columnDefinition = "text") private String criteria;
    @Column(name = "row_count", nullable = false) private long rowCount;
    @Column(nullable = false) private String status = "RESERVED";

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getReservationId() { return reservationId; }
    public void setReservationId(Long v) { reservationId = v; }
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
    public long getRowCount() { return rowCount; }
    public void setRowCount(long v) { rowCount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
}
