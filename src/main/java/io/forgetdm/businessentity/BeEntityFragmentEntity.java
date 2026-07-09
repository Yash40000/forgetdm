package io.forgetdm.businessentity;

import jakarta.persistence.*;
import java.time.Instant;

/** A single member table's captured row-set for one entity instance capsule. */
@Entity
@Table(name = "be_entity_fragments")
public class BeEntityFragmentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "instance_id", nullable = false) private Long instanceId;
    @Column(name = "member_id") private Long memberId;
    @Column(name = "system_name") private String systemName;
    @Column(name = "data_source_id") private Long dataSourceId;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "table_name", nullable = false) private String tableName;
    @Column(name = "key_columns", columnDefinition = "text") private String keyColumns;
    @Column(name = "fragment_type", nullable = false) private String fragmentType = "RAW_REF";
    @Column(nullable = false) private String status = "CURRENT";
    @Column(name = "row_count", nullable = false) private int rowCount;
    /** The capsule version this fragment belongs to — superseded versions stay restorable. */
    @Column(name = "version_no", nullable = false) private int versionNo;
    /** True when the fetch cap clipped the captured row-set (evidence, never silent). */
    @Column(nullable = false) private boolean truncated;
    /** True when payload_json holds AES-256-GCM ciphertext (base64) instead of clear JSON. */
    @Column(nullable = false) private boolean encrypted;
    /** Hex GCM IV/nonce when encrypted. */
    @Column(name = "payload_iv") private String payloadIv;
    @Column(name = "payload_json", columnDefinition = "text") private String payloadJson;
    @Column(name = "content_hash") private String contentHash;
    @Column(columnDefinition = "text") private String message;
    @Column(name = "captured_at") private Instant capturedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long v) { instanceId = v; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long v) { memberId = v; }
    public String getSystemName() { return systemName; }
    public void setSystemName(String v) { systemName = v; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { dataSourceId = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { schemaName = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { tableName = v; }
    public String getKeyColumns() { return keyColumns; }
    public void setKeyColumns(String v) { keyColumns = v; }
    public String getFragmentType() { return fragmentType; }
    public void setFragmentType(String v) { fragmentType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int v) { rowCount = v; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int v) { versionNo = v; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean v) { truncated = v; }
    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean v) { encrypted = v; }
    public String getPayloadIv() { return payloadIv; }
    public void setPayloadIv(String v) { payloadIv = v; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { payloadJson = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { contentHash = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant v) { capturedAt = v; }
}
