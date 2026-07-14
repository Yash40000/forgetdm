package io.forgetdm.unstructured;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "unstructured_masking_jobs")
public class UnstructuredJobEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "profile_id", nullable = false) private Long profileId;
    @Column(name = "profile_version", nullable = false) private int profileVersion;
    @Column(name = "original_filename", nullable = false) private String originalFilename;
    @Column(name = "detected_format") private String detectedFormat;
    @Column(name = "output_strategy") private String outputStrategy;
    @Column(nullable = false) private String status = "QUEUED";
    @Column(nullable = false) private String stage = "QUEUED";
    @Column(nullable = false) private int progress;
    private String message;
    @Column(name = "bytes_read", nullable = false) private long bytesRead;
    @Column(name = "chars_processed", nullable = false) private long charsProcessed;
    @Column(name = "findings_count", nullable = false) private long findingsCount;
    @Column(name = "findings_json", columnDefinition = "text") private String findingsJson;
    @Column(name = "source_sha256") private String sourceSha256;
    @Column(name = "output_sha256") private String outputSha256;
    @JsonIgnore @Column(name = "source_storage_key") private String sourceStorageKey;
    @JsonIgnore @Column(name = "source_key_salt") private String sourceKeySalt;
    @JsonIgnore @Column(name = "source_iv") private String sourceIv;
    @JsonIgnore @Column(name = "output_storage_key") private String outputStorageKey;
    @JsonIgnore @Column(name = "output_key_salt") private String outputKeySalt;
    @JsonIgnore @Column(name = "output_iv") private String outputIv;
    @Column(name = "output_name") private String outputName;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "cancel_requested", nullable = false) private boolean cancelRequested;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;

    public Long getId() { return id; }
    public Long getProfileId() { return profileId; }
    public void setProfileId(Long v) { profileId = v; }
    public int getProfileVersion() { return profileVersion; }
    public void setProfileVersion(int v) { profileVersion = v; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String v) { originalFilename = v; }
    public String getDetectedFormat() { return detectedFormat; }
    public void setDetectedFormat(String v) { detectedFormat = v; }
    public String getOutputStrategy() { return outputStrategy; }
    public void setOutputStrategy(String v) { outputStrategy = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { stage = v; }
    public int getProgress() { return progress; }
    public void setProgress(int v) { progress = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public long getBytesRead() { return bytesRead; }
    public void setBytesRead(long v) { bytesRead = v; }
    public long getCharsProcessed() { return charsProcessed; }
    public void setCharsProcessed(long v) { charsProcessed = v; }
    public long getFindingsCount() { return findingsCount; }
    public void setFindingsCount(long v) { findingsCount = v; }
    public String getFindingsJson() { return findingsJson; }
    public void setFindingsJson(String v) { findingsJson = v; }
    public String getSourceSha256() { return sourceSha256; }
    public void setSourceSha256(String v) { sourceSha256 = v; }
    public String getOutputSha256() { return outputSha256; }
    public void setOutputSha256(String v) { outputSha256 = v; }
    public String getSourceStorageKey() { return sourceStorageKey; }
    public void setSourceStorageKey(String v) { sourceStorageKey = v; }
    public String getSourceKeySalt() { return sourceKeySalt; }
    public void setSourceKeySalt(String v) { sourceKeySalt = v; }
    public String getSourceIv() { return sourceIv; }
    public void setSourceIv(String v) { sourceIv = v; }
    public String getOutputStorageKey() { return outputStorageKey; }
    public void setOutputStorageKey(String v) { outputStorageKey = v; }
    public String getOutputKeySalt() { return outputKeySalt; }
    public void setOutputKeySalt(String v) { outputKeySalt = v; }
    public String getOutputIv() { return outputIv; }
    public void setOutputIv(String v) { outputIv = v; }
    public String getOutputName() { return outputName; }
    public void setOutputName(String v) { outputName = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { errorMessage = v; }
    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean v) { cancelRequested = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { finishedAt = v; }
}
