package io.forgetdm.mainframe;

import jakarta.persistence.*;
import java.time.Instant;

/** A named, reusable COBOL copybook. Different files can reference different copybooks. */
@Entity
@Table(name = "mf_copybooks")
public class CopybookDefEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(nullable = false, columnDefinition = "text") private String source;
    @Column(name = "code_page", nullable = false) private String codePage = "Cp037";
    @Column(name = "record_name") private String recordName;
    @Column(name = "record_length") private Integer recordLength;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public String getCodePage() { return codePage; }
    public void setCodePage(String v) { codePage = v; }
    public String getRecordName() { return recordName; }
    public void setRecordName(String v) { recordName = v; }
    public Integer getRecordLength() { return recordLength; }
    public void setRecordLength(Integer v) { recordLength = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
