package io.forgetdm.sync;

import jakarta.persistence.*;

import java.time.Instant;

/** One coordinated snapshot run over a sync set. */
@Entity
@Table(name = "sync_run")
public class SyncRunEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "sync_set_id", nullable = false) private Long syncSetId;
    @Column(nullable = false) private String status;                 // RUNNING | SUCCESS | PARTIAL | FAILED
    @Column(name = "target_ts") private Instant targetTs;
    @Column(name = "started_at", nullable = false) private Instant startedAt = Instant.now();
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "window_ms") private Long windowMs;
    @Column(name = "member_count", nullable = false) private int memberCount;
    @Column(name = "succeeded_count", nullable = false) private int succeededCount;
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSyncSetId() { return syncSetId; }
    public void setSyncSetId(Long v) { this.syncSetId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getTargetTs() { return targetTs; }
    public void setTargetTs(Instant v) { this.targetTs = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { this.finishedAt = v; }
    public Long getWindowMs() { return windowMs; }
    public void setWindowMs(Long v) { this.windowMs = v; }
    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int v) { this.memberCount = v; }
    public int getSucceededCount() { return succeededCount; }
    public void setSucceededCount(int v) { this.succeededCount = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
}
