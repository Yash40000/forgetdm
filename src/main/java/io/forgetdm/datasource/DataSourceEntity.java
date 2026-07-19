package io.forgetdm.datasource;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "data_sources")
public class DataSourceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false) private String kind;        // POSTGRES | H2 | DB2 | DB2UDB | ORACLE | SQLSERVER | GENERIC
    @Column(name = "jdbc_url", nullable = false) private String jdbcUrl;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    @Column(nullable = false) private String role;        // SOURCE | TARGET | BOTH
    @Column(length = 32) private String environment;      // PROD | UAT | QA | DEV | STAGE | OTHER (free text)
    @Column(columnDefinition = "text") private String tags; // comma-separated labels
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long version;

    // Tenancy (V61 / DEF-0007). visibility: PRIVATE | GROUP | SHARED — legacy rows are SHARED.
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(name = "visibility") private String visibility = "GROUP";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long ownerGroupId) { this.ownerGroupId = ownerGroupId; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
