package io.forgetdm.sync;

import jakarta.persistence.*;

/** A source (+ schema) that belongs to a sync set. */
@Entity
@Table(name = "sync_set_member")
public class SyncSetMemberEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "sync_set_id", nullable = false) private Long syncSetId;
    @Column(name = "data_source_id", nullable = false) private Long dataSourceId;
    @Column(name = "schema_name") private String schemaName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSyncSetId() { return syncSetId; }
    public void setSyncSetId(Long v) { this.syncSetId = v; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { this.dataSourceId = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { this.schemaName = v; }
}
