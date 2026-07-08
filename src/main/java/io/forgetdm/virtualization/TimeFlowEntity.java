package io.forgetdm.virtualization;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A TimeFlow is a lineage of snapshots for one container:
 * either a dSource (registered external database) or a VDB.
 * VDB timeflows record the parent snapshot they branched from.
 */
@Entity
@Table(name = "timeflows")
public class TimeFlowEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String name;
    @Column(name = "container_type", nullable = false) private String containerType; // DSOURCE | VDB
    @Column(name = "source_id") private Long sourceId;
    @Column(name = "vdb_id") private Long vdbId;
    @Column(name = "parent_snapshot_id") private Long parentSnapshotId;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContainerType() { return containerType; }
    public void setContainerType(String containerType) { this.containerType = containerType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getVdbId() { return vdbId; }
    public void setVdbId(Long vdbId) { this.vdbId = vdbId; }
    public Long getParentSnapshotId() { return parentSnapshotId; }
    public void setParentSnapshotId(Long parentSnapshotId) { this.parentSnapshotId = parentSnapshotId; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
