package io.forgetdm.dataset;

import jakarta.persistence.*;

/**
 * Per-table profile within an Access Definition.
 * Controls: inclusion, filter, referential strategy, and per-level Q1/Q2 overrides.
 *
 * referentialStrategy values:
 *   INHERIT       — use the Access Definition's global Q1/Q2 setting (default)
 *   FOLLOW_PARENT — rows constrained by FK join to the parent PK set (standard FK traversal)
 *   INDEPENDENT   — rows selected using this table's own filter_expr, not via FK join;
 *                   table still participates in further Q1/Q2 expansion for its own FK references
 */
@Entity
@Table(name = "table_profiles")
public class TableProfileEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "dataset_id", nullable = false) private Long datasetId;
    @Column(name = "source_data_source_id") private Long sourceDataSourceId;
    @Column(name = "source_schema_name") private String sourceSchemaName;
    @Column(name = "table_name", nullable = false) private String tableName;
    @Column(name = "target_table_name")        private String targetTableName;
    @Column(name = "policy_id")                private Long policyId;
    /** FALSE = completely excluded from extract and FK traversal. */
    @Column(nullable = false)                       private boolean included = true;
    /** SQL WHERE expression applied when selecting rows for this table. */
    @Column(name = "filter_expr", columnDefinition = "text") private String filterExpr;
    /** Optional cap for rows loaded from this table. NULL/0 = no table-specific cap. */
    @Column(name = "row_limit")                    private Integer rowLimit;
    @Column(name = "referential_strategy", nullable = false) private String referentialStrategy = "INHERIT";
    /** NULL = use global Q1; TRUE/FALSE = explicit override for this table only. */
    @Column(name = "q1_override")                   private Boolean q1Override;
    /** NULL = use global Q2; TRUE/FALSE = explicit override for this table only. */
    @Column(name = "q2_override")                   private Boolean q2Override;
    /** Optional explicit load ordering hint. NULL = auto topological. */
    @Column(name = "load_priority")                 private Integer loadPriority;
    @Column(columnDefinition = "text")              private String note;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long v) { datasetId = v; }
    public Long getSourceDataSourceId() { return sourceDataSourceId; }
    public void setSourceDataSourceId(Long v) { sourceDataSourceId = v; }
    public String getSourceSchemaName() { return sourceSchemaName; }
    public void setSourceSchemaName(String v) { sourceSchemaName = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { tableName = v; }
    public String getTargetTableName() { return targetTableName; }
    public void setTargetTableName(String v) { targetTableName = v; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long v) { policyId = v; }
    public boolean isIncluded() { return included; }
    public void setIncluded(boolean v) { included = v; }
    public String getFilterExpr() { return filterExpr; }
    public void setFilterExpr(String v) { filterExpr = v; }
    public Integer getRowLimit() { return rowLimit; }
    public void setRowLimit(Integer v) { rowLimit = v; }
    public String getReferentialStrategy() { return referentialStrategy; }
    public void setReferentialStrategy(String v) { referentialStrategy = v; }
    public Boolean getQ1Override() { return q1Override; }
    public void setQ1Override(Boolean v) { q1Override = v; }
    public Boolean getQ2Override() { return q2Override; }
    public void setQ2Override(Boolean v) { q2Override = v; }
    public Integer getLoadPriority() { return loadPriority; }
    public void setLoadPriority(Integer v) { loadPriority = v; }
    public String getNote() { return note; }
    public void setNote(String v) { note = v; }
}
