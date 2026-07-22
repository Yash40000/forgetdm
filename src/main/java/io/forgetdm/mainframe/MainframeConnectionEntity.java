package io.forgetdm.mainframe;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.Instant;

/** A registered z/OS endpoint ("LPAR"): either a LOCAL landing folder or a real ZOWE/z-OSMF host. */
@Entity
@Table(name = "mf_connections")
public class MainframeConnectionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false) private String type;            // LOCAL | ZOWE
    private String host;
    private Integer port;
    @Column(name = "base_path") private String basePath;      // z/OSMF base path (default /zosmf)
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    @Column(name = "base_dir") private String baseDir;        // LOCAL landing-folder path
    @Column(name = "code_page", nullable = false) private String codePage = "Cp037";
    @Column(name = "trust_all_certs", nullable = false) private boolean trustAllCerts = false;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(name = "visibility") private String visibility = "GROUP";

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getType() { return type; }
    public void setType(String v) { type = v; }
    public String getHost() { return host; }
    public void setHost(String v) { host = v; }
    public Integer getPort() { return port; }
    public void setPort(Integer v) { port = v; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String v) { basePath = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { username = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { password = v; }
    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String v) { baseDir = v; }
    public String getCodePage() { return codePage; }
    public void setCodePage(String v) { codePage = v; }
    public boolean isTrustAllCerts() { return trustAllCerts; }
    public void setTrustAllCerts(boolean v) { trustAllCerts = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { ownerUserId = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long v) { ownerGroupId = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { visibility = v; }
}
