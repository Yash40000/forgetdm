package io.forgetdm.mapping;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "mapping_definition_versions")
public class MappingVersionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "mapping_id", nullable = false) private Long mappingId;
    @Column(name = "version_no", nullable = false) private int versionNo;
    @Column(nullable = false) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "spec_json", columnDefinition = "text", nullable = false) private String specJson;
    @Column(name = "spec_hash", nullable = false) private String specHash;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getMappingId() { return mappingId; }
    public void setMappingId(Long v) { mappingId = v; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int v) { versionNo = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getSpecJson() { return specJson; }
    public void setSpecJson(String v) { specJson = v; }
    public String getSpecHash() { return specHash; }
    public void setSpecHash(String v) { specHash = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
}
