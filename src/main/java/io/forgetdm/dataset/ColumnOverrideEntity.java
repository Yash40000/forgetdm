package io.forgetdm.dataset;

import jakarta.persistence.*;

/**
 * Per-column masking override within an Access Definition.
 * Overrides (or replaces) the policy-level masking rule for a specific column.
 *
 * overrideType values:
 *   USE_POLICY  — apply whatever masking rule the job's policy defines (default)
 *   LITERAL     — write the hardcoded literalValue string; no masking function runs
 *   NULL_OUT    — always write SQL NULL regardless of source value
 *   SUPPRESS    — exclude this column from SELECT/INSERT entirely
 */
@Entity
@Table(name = "column_overrides")
public class ColumnOverrideEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "dataset_id", nullable = false)  private Long datasetId;
    @Column(name = "table_name", nullable = false)  private String tableName;
    @Column(name = "column_name", nullable = false) private String columnName;
    @Column(name = "source_column_name") private String sourceColumnName;
    @Column(name = "override_type", nullable = false) private String overrideType = "USE_POLICY";
    @Column(name = "literal_value", columnDefinition = "text") private String literalValue;
    @Column(columnDefinition = "text")              private String note;

    // ---- conditional masking: apply this override only when the condition is true for the row,
    //      otherwise the original source value passes through unchanged.
    @Column(name = "cond_column")          private String condColumn;        // column to test
    @Column(name = "cond_operator")        private String condOperator;      // EQ|NE|IN|NOT_IN|GT|LT|GTE|LTE|CONTAINS|STARTS_WITH|IS_NULL|IS_NOT_NULL
    @Column(name = "cond_value", columnDefinition = "text") private String condValue;
    @Column(name = "cond_join_table")      private String condJoinTable;     // optional joined table holding condColumn
    @Column(name = "cond_join_source_col") private String condJoinSourceCol; // join key on the source table
    @Column(name = "cond_join_target_col") private String condJoinTargetCol; // join key on the joined table
    // Multi-clause condition (AND/OR): JSON {logic:"ALL|ANY", clauses:[{column,operator,value,joinTable,joinSourceCol,joinTargetCol}]}.
    // When present it takes precedence over the single cond_* columns above.
    @Column(name = "cond_json", columnDefinition = "text") private String condJson;
    // Free-form (recommended): a raw SQL boolean expression and optional JOIN fragment. The source
    // table is aliased "t"; reference it as t.<col> and reference joined tables by your own alias.
    // Evaluated in the database per row; supports any (a AND b) OR c logic and multi-column joins.
    @Column(name = "cond_expr", columnDefinition = "text") private String condExpr;
    @Column(name = "cond_join", columnDefinition = "text") private String condJoin;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long v) { datasetId = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { tableName = v; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String v) { columnName = v; }
    public String getSourceColumnName() { return sourceColumnName; }
    public void setSourceColumnName(String v) { sourceColumnName = v; }
    public String getOverrideType() { return overrideType; }
    public void setOverrideType(String v) { overrideType = v; }
    public String getLiteralValue() { return literalValue; }
    public void setLiteralValue(String v) { literalValue = v; }
    public String getNote() { return note; }
    public void setNote(String v) { note = v; }
    public String getCondColumn() { return condColumn; }
    public void setCondColumn(String v) { condColumn = v; }
    public String getCondOperator() { return condOperator; }
    public void setCondOperator(String v) { condOperator = v; }
    public String getCondValue() { return condValue; }
    public void setCondValue(String v) { condValue = v; }
    public String getCondJoinTable() { return condJoinTable; }
    public void setCondJoinTable(String v) { condJoinTable = v; }
    public String getCondJoinSourceCol() { return condJoinSourceCol; }
    public void setCondJoinSourceCol(String v) { condJoinSourceCol = v; }
    public String getCondJoinTargetCol() { return condJoinTargetCol; }
    public void setCondJoinTargetCol(String v) { condJoinTargetCol = v; }
    public String getCondJson() { return condJson; }
    public void setCondJson(String v) { condJson = v; }
    public String getCondExpr() { return condExpr; }
    public void setCondExpr(String v) { condExpr = v; }
    public String getCondJoin() { return condJoin; }
    public void setCondJoin(String v) { condJoin = v; }
}
