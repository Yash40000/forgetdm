package io.forgetdm.dataset;

import jakarta.persistence.*;

/**
 * Tool-level primary key override for a table within a DataScope blueprint.
 *
 * Used when the database catalog has no PK or unique constraint on the table
 * (common on DB2 z/OS unload files, legacy schemas, or cross-schema joins).
 * The tool uses these column(s) to identify rows and drive FK closure instead
 * of querying the catalog.
 *
 * columnNames is a comma-separated ordered list of columns that form the logical PK.
 */
@Entity
@Table(name = "user_defined_pks")
public class UserDefinedPkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    /** Comma-separated ordered list of columns that form the logical primary key. */
    @Column(name = "column_names", nullable = false)
    private String columnNames;

    @Column(columnDefinition = "text")
    private String note;

    // ── getters / setters ────────────────────────────────────────────────────

    public Long getId()                  { return id; }
    public void setId(Long v)            { id = v; }
    public Long getDatasetId()           { return datasetId; }
    public void setDatasetId(Long v)     { datasetId = v; }
    public String getTableName()         { return tableName; }
    public void setTableName(String v)   { tableName = v; }
    public String getColumnNames()       { return columnNames; }
    public void setColumnNames(String v) { columnNames = v; }
    public String getNote()              { return note; }
    public void setNote(String v)        { note = v; }
}
