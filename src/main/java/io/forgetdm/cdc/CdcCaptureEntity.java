package io.forgetdm.cdc;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One CDC capture per data source. Owns the logical-replication slot and the LSN checkpoint
 * so capture is resumable across restarts. Tenancy columns mirror the V61 core-object model.
 */
@Entity
@Table(name = "cdc_capture")
public class CdcCaptureEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @Column(name = "data_source_id", nullable = false) private Long dataSourceId;
    @Column(name = "slot_name", nullable = false) private String slotName;
    @Column(nullable = false) private String plugin = "test_decoding";
    @Column(name = "publication_name") private String publicationName;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "tables_json") private String tablesJson;
    @Column(nullable = false) private String status = "INACTIVE";     // INACTIVE | ACTIVE | ERROR
    @Column(name = "confirmed_lsn") private String confirmedLsn;
    @Column(name = "restart_lsn") private String restartLsn;
    @Column(name = "rows_captured", nullable = false) private long rowsCaptured = 0;
    @Column(name = "last_error") private String lastError;
    @Column(name = "last_polled_at") private Instant lastPolledAt;

    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(nullable = false) private String visibility = "GROUP";

    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { this.dataSourceId = v; }
    public String getSlotName() { return slotName; }
    public void setSlotName(String v) { this.slotName = v; }
    public String getPlugin() { return plugin; }
    public void setPlugin(String v) { this.plugin = v; }
    public String getPublicationName() { return publicationName; }
    public void setPublicationName(String v) { this.publicationName = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { this.schemaName = v; }
    public String getTablesJson() { return tablesJson; }
    public void setTablesJson(String v) { this.tablesJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getConfirmedLsn() { return confirmedLsn; }
    public void setConfirmedLsn(String v) { this.confirmedLsn = v; }
    public String getRestartLsn() { return restartLsn; }
    public void setRestartLsn(String v) { this.restartLsn = v; }
    public long getRowsCaptured() { return rowsCaptured; }
    public void setRowsCaptured(long v) { this.rowsCaptured = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }
    public Instant getLastPolledAt() { return lastPolledAt; }
    public void setLastPolledAt(Instant v) { this.lastPolledAt = v; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { this.ownerUserId = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { this.ownerUsername = v; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long v) { this.ownerGroupId = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { this.visibility = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
