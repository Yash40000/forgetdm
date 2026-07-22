package io.forgetdm.virtualization;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "virtual_snapshots")
public class VirtualSnapshotEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String name;
    @Column(name = "snapshot_type", nullable = false) private String snapshotType;
    @Column(name = "source_id") private Long sourceId;
    @Column(name = "vdb_id") private Long vdbId;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "storage_path", nullable = false) private String storagePath;
    @Column(name = "table_count") private int tableCount;
    @Column(name = "row_count") private long rowCount;
    private String note;
    @Lob
    @Column(name = "script_sql") private String scriptSql; // legacy pre-TimeFlow snapshots only
    @Column(name = "timeflow_id") private Long timeflowId;
    @Column(nullable = false) private String provider = "POOL"; // POOL | CONTAINER
    @Column(name = "image_ref") private String imageRef;        // docker image tag for CONTAINER snapshots
    @Column(name = "manifest_hash") private String manifestHash;
    @Column(name = "chunk_count") private int chunkCount;
    @Column(name = "new_chunk_count") private int newChunkCount;
    @Column(name = "logical_bytes") private long logicalBytes;
    @Column(name = "stored_bytes") private long storedBytes;
    @Column(name = "cdc_capture_id") private Long cdcCaptureId;
    @Column(name = "cdc_base_snapshot_id") private Long cdcBaseSnapshotId;
    @Column(name = "cdc_from_position") private String cdcFromPosition;
    @Column(name = "cdc_through_position") private String cdcThroughPosition;
    @Column(name = "cdc_through_change_id") private Long cdcThroughChangeId;
    @Column(name = "cdc_through_ts") private Instant cdcThroughTs;
    @Column(name = "cdc_changes_applied") private long cdcChangesApplied;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getVdbId() { return vdbId; }
    public void setVdbId(Long vdbId) { this.vdbId = vdbId; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public int getTableCount() { return tableCount; }
    public void setTableCount(int tableCount) { this.tableCount = tableCount; }
    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    @JsonIgnore
    public String getScriptSql() { return scriptSql; }
    public void setScriptSql(String scriptSql) { this.scriptSql = scriptSql; }
    public Long getTimeflowId() { return timeflowId; }
    public void setTimeflowId(Long timeflowId) { this.timeflowId = timeflowId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getImageRef() { return imageRef; }
    public void setImageRef(String imageRef) { this.imageRef = imageRef; }
    public String getManifestHash() { return manifestHash; }
    public void setManifestHash(String manifestHash) { this.manifestHash = manifestHash; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public int getNewChunkCount() { return newChunkCount; }
    public void setNewChunkCount(int newChunkCount) { this.newChunkCount = newChunkCount; }
    public long getLogicalBytes() { return logicalBytes; }
    public void setLogicalBytes(long logicalBytes) { this.logicalBytes = logicalBytes; }
    public long getStoredBytes() { return storedBytes; }
    public void setStoredBytes(long storedBytes) { this.storedBytes = storedBytes; }
    public Long getCdcCaptureId() { return cdcCaptureId; }
    public void setCdcCaptureId(Long cdcCaptureId) { this.cdcCaptureId = cdcCaptureId; }
    public Long getCdcBaseSnapshotId() { return cdcBaseSnapshotId; }
    public void setCdcBaseSnapshotId(Long cdcBaseSnapshotId) { this.cdcBaseSnapshotId = cdcBaseSnapshotId; }
    public String getCdcFromPosition() { return cdcFromPosition; }
    public void setCdcFromPosition(String cdcFromPosition) { this.cdcFromPosition = cdcFromPosition; }
    public String getCdcThroughPosition() { return cdcThroughPosition; }
    public void setCdcThroughPosition(String cdcThroughPosition) { this.cdcThroughPosition = cdcThroughPosition; }
    public Long getCdcThroughChangeId() { return cdcThroughChangeId; }
    public void setCdcThroughChangeId(Long cdcThroughChangeId) { this.cdcThroughChangeId = cdcThroughChangeId; }
    public Instant getCdcThroughTs() { return cdcThroughTs; }
    public void setCdcThroughTs(Instant cdcThroughTs) { this.cdcThroughTs = cdcThroughTs; }
    public long getCdcChangesApplied() { return cdcChangesApplied; }
    public void setCdcChangesApplied(long cdcChangesApplied) { this.cdcChangesApplied = cdcChangesApplied; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
