package io.forgetdm.mainframe;

import jakarta.persistence.*;

/** One file within a mainframe job, with its own copybook, record format, and optional target LPAR/name. */
@Entity
@Table(name = "mf_job_files")
public class MainframeJobFileEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "job_id", nullable = false) private Long jobId;
    @Column(name = "source_name", nullable = false) private String sourceName;
    @Column(name = "copybook_id") private Long copybookId;
    @Column(nullable = false) private String recfm = "FB";       // FB | VB
    private Integer lrecl;
    @Column(name = "code_page") private String codePage;
    @Column(name = "target_connection_id") private Long targetConnectionId; // null = job target
    @Column(name = "target_name") private String targetName;     // null = same as source
    @Column(nullable = false) private String status = "PENDING";
    @Column(name = "record_count") private long recordCount;
    @Column(columnDefinition = "text") private String message;
    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long v) { jobId = v; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String v) { sourceName = v; }
    public Long getCopybookId() { return copybookId; }
    public void setCopybookId(Long v) { copybookId = v; }
    public String getRecfm() { return recfm; }
    public void setRecfm(String v) { recfm = v; }
    public Integer getLrecl() { return lrecl; }
    public void setLrecl(Integer v) { lrecl = v; }
    public String getCodePage() { return codePage; }
    public void setCodePage(String v) { codePage = v; }
    public Long getTargetConnectionId() { return targetConnectionId; }
    public void setTargetConnectionId(Long v) { targetConnectionId = v; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String v) { targetName = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public long getRecordCount() { return recordCount; }
    public void setRecordCount(long v) { recordCount = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int v) { ordinal = v; }
}
