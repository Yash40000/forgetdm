package io.forgetdm.policy;

import jakarta.persistence.*;

@Entity
@Table(name = "masking_rules")
public class MaskingRuleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "policy_id", nullable = false) private Long policyId;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "table_name", nullable = false) private String tableName;
    @Column(name = "column_name", nullable = false) private String columnName;
    @Column(name = "function", nullable = false) private String function;
    private String param1;
    private String param2;
    private boolean deterministic = true;

    public Long getId() { return id; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long policyId) { this.policyId = policyId; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public String getFunction() { return function; }
    public void setFunction(String function) { this.function = function; }
    public String getParam1() { return param1; }
    public void setParam1(String param1) { this.param1 = param1; }
    public String getParam2() { return param2; }
    public void setParam2(String param2) { this.param2 = param2; }
    public boolean isDeterministic() { return deterministic; }
    public void setDeterministic(boolean deterministic) { this.deterministic = deterministic; }
}
