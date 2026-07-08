package io.forgetdm.dataset;

import jakarta.persistence.*;
import java.time.Instant;

/** An immutable JSON snapshot of a DataScope blueprint at a point in time (change control). */
@Entity
@Table(name = "datascope_versions")
public class DataSetVersionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "dataset_id", nullable = false) private Long datasetId;
    @Column(name = "version_no", nullable = false) private int versionNo;
    @Column private String note;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "snapshot_json", columnDefinition = "text", nullable = false) private String snapshotJson;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long v) { datasetId = v; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int v) { versionNo = v; }
    public String getNote() { return note; }
    public void setNote(String v) { note = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String v) { snapshotJson = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
}
