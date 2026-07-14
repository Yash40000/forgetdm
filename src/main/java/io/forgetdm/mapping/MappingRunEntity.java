package io.forgetdm.mapping;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "mapping_execution_runs")
public class MappingRunEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "mapping_id", nullable = false) private Long mappingId;
    @Column(name = "mapping_version", nullable = false) private int mappingVersion;
    @Column(name = "run_type", nullable = false) private String runType = "AUTO_PROVISION";
    @Column(nullable = false) private String status = "QUEUED";
    @Column(nullable = false) private String stage = "QUEUED";
    @Column(nullable = false) private int progress;
    private String message;
    @Column(name = "rows_read", nullable = false) private long rowsRead;
    @Column(name = "rows_written", nullable = false) private long rowsWritten;
    @Column(name = "rows_rejected", nullable = false) private long rowsRejected;
    @Column(name = "request_json", columnDefinition = "text", nullable = false) private String requestJson = "{}";
    @Column(name = "result_json", columnDefinition = "text") private String resultJson;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "output_name") private String outputName;
    @Column(name = "output_format") private String outputFormat;
    @JsonIgnore @Column(name = "output_storage_key") private String outputStorageKey;
    @JsonIgnore @Column(name = "output_key_salt") private String outputKeySalt;
    @JsonIgnore @Column(name = "output_iv") private String outputIv;
    @Column(name = "output_sha256") private String outputSha256;
    @Column(name = "cancel_requested", nullable = false) private boolean cancelRequested;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;

    public Long getId() { return id; }
    public Long getMappingId() { return mappingId; }
    public void setMappingId(Long v) { mappingId = v; }
    public int getMappingVersion() { return mappingVersion; }
    public void setMappingVersion(int v) { mappingVersion = v; }
    public String getRunType() { return runType; }
    public void setRunType(String v) { runType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { stage = v; }
    public int getProgress() { return progress; }
    public void setProgress(int v) { progress = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public long getRowsRead() { return rowsRead; }
    public void setRowsRead(long v) { rowsRead = v; }
    public long getRowsWritten() { return rowsWritten; }
    public void setRowsWritten(long v) { rowsWritten = v; }
    public long getRowsRejected() { return rowsRejected; }
    public void setRowsRejected(long v) { rowsRejected = v; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String v) { requestJson = v; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String v) { resultJson = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { errorMessage = v; }
    public String getOutputName() { return outputName; }
    public void setOutputName(String v) { outputName = v; }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String v) { outputFormat = v; }
    public String getOutputStorageKey() { return outputStorageKey; }
    public void setOutputStorageKey(String v) { outputStorageKey = v; }
    public String getOutputKeySalt() { return outputKeySalt; }
    public void setOutputKeySalt(String v) { outputKeySalt = v; }
    public String getOutputIv() { return outputIv; }
    public void setOutputIv(String v) { outputIv = v; }
    public String getOutputSha256() { return outputSha256; }
    public void setOutputSha256(String v) { outputSha256 = v; }
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
