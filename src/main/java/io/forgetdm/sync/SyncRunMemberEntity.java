package io.forgetdm.sync;

import jakarta.persistence.*;

/** Per-source result within a coordinated run: snapshot id, consistency point, rows, status. */
@Entity
@Table(name = "sync_run_member")
public class SyncRunMemberEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "sync_run_id", nullable = false) private Long syncRunId;
    @Column(name = "data_source_id", nullable = false) private Long dataSourceId;
    @Column(name = "data_source_name") private String dataSourceName;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "snapshot_id") private Long snapshotId;
    @Column(name = "consistency_point") private String consistencyPoint;
    private String mechanism;
    @Column(name = "row_count") private Long rowCount;
    @Column(nullable = false) private String status;   // SUCCESS | FAILED
    private String error;
    @Column(name = "elapsed_ms") private Long elapsedMs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSyncRunId() { return syncRunId; }
    public void setSyncRunId(Long v) { this.syncRunId = v; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { this.dataSourceId = v; }
    public String getDataSourceName() { return dataSourceName; }
    public void setDataSourceName(String v) { this.dataSourceName = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { this.schemaName = v; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long v) { this.snapshotId = v; }
    public String getConsistencyPoint() { return consistencyPoint; }
    public void setConsistencyPoint(String v) { this.consistencyPoint = v; }
    public String getMechanism() { return mechanism; }
    public void setMechanism(String v) { this.mechanism = v; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long v) { this.rowCount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long v) { this.elapsedMs = v; }
}
