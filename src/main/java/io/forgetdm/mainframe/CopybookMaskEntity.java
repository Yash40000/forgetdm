package io.forgetdm.mainframe;

import jakarta.persistence.*;

/** One field -> masking-function binding within a copybook's field map. */
@Entity
@Table(name = "mf_copybook_masks")
public class CopybookMaskEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "copybook_id", nullable = false) private Long copybookId;
    @Column(name = "field_path", nullable = false) private String fieldPath;   // structural path, no OCCURS subscripts
    @Column(name = "function", nullable = false) private String function;      // MaskFunction name
    private String param1;
    private String param2;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getCopybookId() { return copybookId; }
    public void setCopybookId(Long v) { copybookId = v; }
    public String getFieldPath() { return fieldPath; }
    public void setFieldPath(String v) { fieldPath = v; }
    public String getFunction() { return function; }
    public void setFunction(String v) { function = v; }
    public String getParam1() { return param1; }
    public void setParam1(String v) { param1 = v; }
    public String getParam2() { return param2; }
    public void setParam2(String v) { param2 = v; }
}
