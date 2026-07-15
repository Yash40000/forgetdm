package io.forgetdm.dataset;

import jakarta.persistence.*;

/**
 * Per-relationship traversal control within a DataScope blueprint.
 *
 * Supersedes the coarser per-table q1_override / q2_override on table_profiles.
 * A rule targets a specific FK edge (parentTable → childTable) and specifies
 * whether it is followed in the Q1 direction (child → parent lookup), Q2 direction
 * (parent → child lookup), both, or neither.
 *
 * When both a traversal rule and a table-profile Q1/Q2 override exist for the same
 * table pair, the traversal rule takes precedence (more specific wins).
 *
 * traverseDirection values:
 *   BOTH     — traverse in both directions (default)
 *   Q1_ONLY  — traverse only child-to-parent (referential integrity / parent pull)
 *   Q2_ONLY  — traverse only parent-to-child (entity completeness / child pull)
 *   NONE     — do not traverse this relationship in any direction
 *
 * relSource = 'DB'   for catalog-level FK constraints
 * relSource = 'USER' for user_defined_relationships (relRefId points to that row)
 */
@Entity
@Table(name = "relationship_traversal_rules")
public class RelationshipTraversalRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "parent_table", nullable = false)
    private String parentTable;

    @Column(name = "child_table", nullable = false)
    private String childTable;

    /** 'DB' = catalog FK; 'USER' = user_defined_relationships row. */
    @Column(name = "rel_source", nullable = false)
    private String relSource = "DB";

    /** FK to user_defined_relationships.id when relSource = 'USER'. Null for 'DB' rules. */
    @Column(name = "rel_ref_id")
    private Long relRefId;

    /** INHERIT | BOTH | Q1_ONLY | Q2_ONLY | NONE */
    @Column(name = "traverse_direction", nullable = false)
    private String traverseDirection = "BOTH";

    /** Lower value = earlier traversal order within the same table (reserved for future use). */
    @Column
    private int priority = 0;

    @Column(columnDefinition = "text")
    private String note;

    // ── getters / setters ────────────────────────────────────────────────────

    public Long getId()                        { return id; }
    public void setId(Long v)                  { id = v; }
    public Long getDatasetId()                 { return datasetId; }
    public void setDatasetId(Long v)           { datasetId = v; }
    public String getParentTable()             { return parentTable; }
    public void setParentTable(String v)       { parentTable = v; }
    public String getChildTable()              { return childTable; }
    public void setChildTable(String v)        { childTable = v; }
    public String getRelSource()               { return relSource; }
    public void setRelSource(String v)         { relSource = v; }
    public Long getRelRefId()                  { return relRefId; }
    public void setRelRefId(Long v)            { relRefId = v; }
    public String getTraverseDirection()       { return traverseDirection; }
    public void setTraverseDirection(String v) { traverseDirection = v; }
    public int getPriority()                   { return priority; }
    public void setPriority(int v)             { priority = v; }
    public String getNote()                    { return note; }
    public void setNote(String v)              { note = v; }
}
