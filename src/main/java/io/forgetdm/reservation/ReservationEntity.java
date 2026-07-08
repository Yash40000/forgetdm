package io.forgetdm.reservation;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reservations")
public class ReservationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "data_source_id", nullable = false) private Long dataSourceId;
    @Column(name = "table_name", nullable = false) private String tableName;
    private String criteria;
    @Column(name = "row_keys_json", nullable = false, columnDefinition = "text") private String rowKeysJson;
    @Column(name = "reserved_by", nullable = false) private String reservedBy;
    private String purpose;
    @Column(nullable = false) private String status = "ACTIVE";
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { dataSourceId = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { tableName = v; }
    public String getCriteria() { return criteria; }
    public void setCriteria(String v) { criteria = v; }
    public String getRowKeysJson() { return rowKeysJson; }
    public void setRowKeysJson(String v) { rowKeysJson = v; }
    public String getReservedBy() { return reservedBy; }
    public void setReservedBy(String v) { reservedBy = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { purpose = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { expiresAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
