package io.forgetdm.dataset;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Tool-level FK relationship defined by the user within a DataScope blueprint.
 *
 * Captures implicit or soft foreign-key relationships that are not enforced in
 * the database catalog (common on mainframe schemas, cross-schema joins, or
 * legacy applications where FK constraints were never created).
 *
 * parentColumns and childColumns are comma-separated parallel arrays:
 *   parentColumns[i] in parentTable corresponds to childColumns[i] in childTable.
 *
 * Example — a multi-column relationship:
 *   parentTable   = POLICY
 *   parentColumns = COMPANY_CODE,POLICY_NO
 *   childTable    = CLAIM
 *   childColumns  = COMP_CD,POL_NUMBER
 */
@Entity
@Table(name = "user_defined_relationships")
public class UserDefinedRelationshipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    /** Optional human-readable label for this relationship (e.g. "Policy → Claim"). */
    @Column(name = "rel_name")
    private String relName;

    @Column(name = "parent_table", nullable = false)
    private String parentTable;

    /** Comma-separated parent-side column(s). */
    @Column(name = "parent_columns", nullable = false)
    private String parentColumns;

    @Column(name = "child_table", nullable = false)
    private String childTable;

    /** Comma-separated child-side column(s), positionally aligned with parentColumns. */
    @Column(name = "child_columns", nullable = false)
    private String childColumns;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── getters / setters ────────────────────────────────────────────────────

    public Long getId()                    { return id; }
    public Long getDatasetId()             { return datasetId; }
    public void setDatasetId(Long v)       { datasetId = v; }
    public String getRelName()             { return relName; }
    public void setRelName(String v)       { relName = v; }
    public String getParentTable()         { return parentTable; }
    public void setParentTable(String v)   { parentTable = v; }
    public String getParentColumns()       { return parentColumns; }
    public void setParentColumns(String v) { parentColumns = v; }
    public String getChildTable()          { return childTable; }
    public void setChildTable(String v)    { childTable = v; }
    public String getChildColumns()        { return childColumns; }
    public void setChildColumns(String v)  { childColumns = v; }
    public String getNote()                { return note; }
    public void setNote(String v)          { note = v; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
}
