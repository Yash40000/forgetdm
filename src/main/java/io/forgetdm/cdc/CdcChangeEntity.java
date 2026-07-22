package io.forgetdm.cdc;

import jakarta.persistence.*;

import java.time.Instant;

/** Append-only buffer of decoded row changes read from the transaction log — the change feed. */
@Entity
@Table(name = "cdc_change")
public class CdcChangeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @Column(name = "capture_id", nullable = false) private Long captureId;
    @Column(name = "data_source_id", nullable = false) private Long dataSourceId;
    private String lsn;
    private Long xid;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "table_name", nullable = false) private String tableName;
    @Column(nullable = false) private String op;          // I | U | D
    @Column(name = "pk_json") private String pkJson;
    @Column(name = "change_json") private String changeJson;
    @Column(name = "captured_at", nullable = false) private Instant capturedAt = Instant.now();

    public CdcChangeEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCaptureId() { return captureId; }
    public void setCaptureId(Long v) { this.captureId = v; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { this.dataSourceId = v; }
    public String getLsn() { return lsn; }
    public void setLsn(String v) { this.lsn = v; }
    public Long getXid() { return xid; }
    public void setXid(Long v) { this.xid = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { this.schemaName = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { this.tableName = v; }
    public String getOp() { return op; }
    public void setOp(String v) { this.op = v; }
    public String getPkJson() { return pkJson; }
    public void setPkJson(String v) { this.pkJson = v; }
    public String getChangeJson() { return changeJson; }
    public void setChangeJson(String v) { this.changeJson = v; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant v) { this.capturedAt = v; }
}
