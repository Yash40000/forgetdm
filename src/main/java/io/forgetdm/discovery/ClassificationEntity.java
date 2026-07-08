package io.forgetdm.discovery;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "classifications")
public class ClassificationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "data_source_id", nullable = false) private Long dataSourceId;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "table_name", nullable = false) private String tableName;
    @Column(name = "column_name", nullable = false) private String columnName;
    @Column(name = "data_type") private String dataType;
    @Column(name = "pii_type", nullable = false) private String piiType;
    @Column(nullable = false) private double confidence;
    @Column(name = "suggested_function") private String suggestedFunction;
    @Column(name = "suggested_param1") private String suggestedParam1;
    @Column(name = "suggested_param2") private String suggestedParam2;
    @Column(nullable = false) private String status = "SUGGESTED";
    @Column(name = "sample_value") private String sampleValue;
    @Column(name = "discovered_at") private Instant discoveredAt = Instant.now();

    public Long getId() { return id; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { dataSourceId = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { schemaName = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { tableName = v; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String v) { columnName = v; }
    public String getDataType() { return dataType; }
    public void setDataType(String v) { dataType = v; }
    public String getPiiType() { return piiType; }
    public void setPiiType(String v) { piiType = v; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { confidence = v; }
    public String getSuggestedFunction() { return suggestedFunction; }
    public void setSuggestedFunction(String v) { suggestedFunction = v; }
    public String getSuggestedParam1() { return suggestedParam1; }
    public void setSuggestedParam1(String v) { suggestedParam1 = v; }
    public String getSuggestedParam2() { return suggestedParam2; }
    public void setSuggestedParam2(String v) { suggestedParam2 = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getSampleValue() { return sampleValue; }
    public void setSampleValue(String v) { sampleValue = v; }
    public Instant getDiscoveredAt() { return discoveredAt; }
}
