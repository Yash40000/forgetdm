package io.forgetdm.mapping;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "mapping_file_assets")
public class MappingFileAssetEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String format;
    @Column(name = "original_filename", nullable = false) private String originalFilename;
    @Column(name = "content_type") private String contentType;
    @Column(name = "size_bytes", nullable = false) private long sizeBytes;
    @Column(nullable = false) private String sha256;
    @JsonIgnore @Column(name = "storage_key", nullable = false) private String storageKey;
    @JsonIgnore @Column(name = "key_salt", nullable = false) private String keySalt;
    @JsonIgnore @Column(name = "payload_iv", nullable = false) private String payloadIv;
    @Column(name = "options_json", columnDefinition = "text", nullable = false) private String optionsJson = "{}";
    @Column(name = "schema_json", columnDefinition = "text", nullable = false) private String schemaJson = "[]";
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(nullable = false) private String visibility = "GROUP";
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getFormat() { return format; }
    public void setFormat(String v) { format = v; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String v) { originalFilename = v; }
    public String getContentType() { return contentType; }
    public void setContentType(String v) { contentType = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { sizeBytes = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { sha256 = v; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String v) { storageKey = v; }
    public String getKeySalt() { return keySalt; }
    public void setKeySalt(String v) { keySalt = v; }
    public String getPayloadIv() { return payloadIv; }
    public void setPayloadIv(String v) { payloadIv = v; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String v) { optionsJson = v; }
    public String getSchemaJson() { return schemaJson; }
    public void setSchemaJson(String v) { schemaJson = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { ownerUserId = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long v) { ownerGroupId = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { visibility = v; }
    public Instant getCreatedAt() { return createdAt; }
}
