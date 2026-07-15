package io.forgetdm.virtualization;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "virtual_databases")
public class VirtualDatabaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(name = "source_snapshot_id", nullable = false) private Long sourceSnapshotId;
    @Column(name = "current_snapshot_id") private Long currentSnapshotId;
    @Column(name = "data_source_id") private Long dataSourceId;
    @Column(name = "jdbc_url", nullable = false) private String jdbcUrl;
    @Column(name = "schema_name") private String schemaName;
    private String username;
    private String password;
    @Column(name = "storage_path", nullable = false) private String storagePath;
    @Column(nullable = false) private String status = "ACTIVE";
    @Column(name = "timeflow_id") private Long timeflowId;
    @Column(nullable = false) private String provider = "POOL"; // POOL | CONTAINER
    @Column(name = "container_id") private String containerId;
    @Column(name = "host_port") private Integer hostPort;
    @Column(name = "environment_id") private Long environmentId;
    @Column(name = "target_kind") private String targetKind = "H2";
    @Column(name = "target_data_source_id") private Long targetDataSourceId;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getSourceSnapshotId() { return sourceSnapshotId; }
    public void setSourceSnapshotId(Long sourceSnapshotId) { this.sourceSnapshotId = sourceSnapshotId; }
    public Long getCurrentSnapshotId() { return currentSnapshotId; }
    public void setCurrentSnapshotId(Long currentSnapshotId) { this.currentSnapshotId = currentSnapshotId; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTimeflowId() { return timeflowId; }
    public void setTimeflowId(Long timeflowId) { this.timeflowId = timeflowId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }
    public Integer getHostPort() { return hostPort; }
    public void setHostPort(Integer hostPort) { this.hostPort = hostPort; }
    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String targetKind) { this.targetKind = targetKind; }
    public Long getTargetDataSourceId() { return targetDataSourceId; }
    public void setTargetDataSourceId(Long targetDataSourceId) { this.targetDataSourceId = targetDataSourceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
