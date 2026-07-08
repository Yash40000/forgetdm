package io.forgetdm.mainframe;

import jakarta.persistence.*;
import java.time.Instant;

/** A batch mainframe file-masking job: fetch many files, mask, write back to a (same or different) LPAR. */
@Entity
@Table(name = "mf_jobs")
public class MainframeJobEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String status = "PENDING";
    @Column(name = "source_connection_id") private Long sourceConnectionId;
    @Column(name = "target_connection_id") private Long targetConnectionId;
    @Column(name = "masking_seed") private String maskingSeed;
    @Column(columnDefinition = "text") private String message;
    @Column(name = "files_total") private int filesTotal;
    @Column(name = "files_done") private int filesDone;
    @Column(name = "records_processed") private long recordsProcessed;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Long getSourceConnectionId() { return sourceConnectionId; }
    public void setSourceConnectionId(Long v) { sourceConnectionId = v; }
    public Long getTargetConnectionId() { return targetConnectionId; }
    public void setTargetConnectionId(Long v) { targetConnectionId = v; }
    public String getMaskingSeed() { return maskingSeed; }
    public void setMaskingSeed(String v) { maskingSeed = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public int getFilesTotal() { return filesTotal; }
    public void setFilesTotal(int v) { filesTotal = v; }
    public int getFilesDone() { return filesDone; }
    public void setFilesDone(int v) { filesDone = v; }
    public long getRecordsProcessed() { return recordsProcessed; }
    public void setRecordsProcessed(long v) { recordsProcessed = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { finishedAt = v; }
}
