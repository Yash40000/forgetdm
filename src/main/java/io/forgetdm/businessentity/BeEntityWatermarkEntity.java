package io.forgetdm.businessentity;

import jakarta.persistence.*;
import java.time.Instant;

/** Freshness evidence for one member of an entity instance capsule — either captured at
 *  materialization time, or propagated from a live Business Entity sync/freshness check. */
@Entity
@Table(name = "be_entity_watermarks")
public class BeEntityWatermarkEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "instance_id", nullable = false) private Long instanceId;
    @Column(name = "member_id") private Long memberId;
    @Column(name = "table_name") private String tableName;
    @Column(name = "watermark_column") private String watermarkColumn;
    @Column(name = "watermark_value") private String watermarkValue;
    @Column(nullable = false) private String status = "UNKNOWN";
    @Column(columnDefinition = "text") private String message;
    @Column(nullable = false) private String source = "CAPTURE";
    @Column(name = "checked_at") private Instant checkedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long v) { instanceId = v; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long v) { memberId = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { tableName = v; }
    public String getWatermarkColumn() { return watermarkColumn; }
    public void setWatermarkColumn(String v) { watermarkColumn = v; }
    public String getWatermarkValue() { return watermarkValue; }
    public void setWatermarkValue(String v) { watermarkValue = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant v) { checkedAt = v; }
}
