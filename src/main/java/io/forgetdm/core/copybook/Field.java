package io.forgetdm.core.copybook;

import java.util.ArrayList;
import java.util.List;

/**
 * One node in a parsed copybook tree.
 *
 * A field is either a GROUP (has children, no PICTURE) or ELEMENTARY (has a PICTURE / RAW usage).
 * Level-66 (RENAMES) and level-88 (condition-name) entries are carried separately because they
 * describe re-groupings / value sets rather than storage.
 *
 * Layout fields ({@link #offset} and {@link #length}) are filled in by {@link LayoutComputer}.
 * For OCCURS items {@link #length} is the length of ONE occurrence; total storage is
 * {@code length * occursMax}. With OCCURS DEPENDING ON the true count is resolved per record by
 * {@link RecordCodec}; the static layout uses {@link #occursMax}.
 */
public final class Field {

    public record Condition(String name, List<String> values) {}

    private final int level;
    private final String name;

    // elementary attributes
    private String rawPicture;
    private Picture picture;
    private Usage usage;                 // null => inherit from ancestor, default DISPLAY
    private boolean signLeading;
    private boolean signSeparate;
    private boolean sync;

    // table attributes
    private int occursMin = 1;
    private int occursMax = 1;
    private String dependingOn;          // OCCURS DEPENDING ON target (unqualified name)

    // overlay / rename
    private String redefines;            // REDEFINES target name
    private String renamesFrom;          // level 66: RENAMES a
    private String renamesThru;          // level 66: ... THRU b

    private final List<Field> children = new ArrayList<>();
    private final List<Condition> conditions = new ArrayList<>();
    private Field parent;

    // computed layout (set by LayoutComputer)
    private int offset = -1;             // byte offset within the record (first occurrence)
    private int length = -1;             // byte length of a single occurrence

    public Field(int level, String name) {
        this.level = level;
        this.name = name;
    }

    // ---- structure
    public int level()              { return level; }
    public String name()            { return name; }
    public List<Field> children()   { return children; }
    public List<Condition> conditions() { return conditions; }
    public Field parent()           { return parent; }
    void setParent(Field p)         { this.parent = p; }
    public boolean isGroup()        { return !children.isEmpty(); }
    public boolean isElementary()   { return children.isEmpty(); }
    public boolean isFiller()       { return name == null || name.equalsIgnoreCase("FILLER"); }
    public boolean occurs()         { return occursMax > 1 || dependingOn != null; }
    public boolean isRename()       { return level == 66; }

    // ---- elementary attributes
    public String rawPicture()      { return rawPicture; }
    void setRawPicture(String p)    { this.rawPicture = p; }
    public Picture picture()        { return picture; }
    void setPicture(Picture p)      { this.picture = p; }
    public Usage usage()            { return usage; }
    void setUsage(Usage u)          { this.usage = u; }
    public boolean signLeading()    { return signLeading; }
    void setSignLeading(boolean b)  { this.signLeading = b; }
    public boolean signSeparate()   { return signSeparate; }
    void setSignSeparate(boolean b) { this.signSeparate = b; }
    public boolean sync()           { return sync; }
    void setSync(boolean b)         { this.sync = b; }

    // ---- table attributes
    public int occursMin()          { return occursMin; }
    public int occursMax()          { return occursMax; }
    void setOccurs(int min, int max){ this.occursMin = min; this.occursMax = max; }
    public String dependingOn()     { return dependingOn; }
    void setDependingOn(String d)   { this.dependingOn = d; }

    // ---- overlay / rename
    public String redefines()       { return redefines; }
    void setRedefines(String r)     { this.redefines = r; }
    public String renamesFrom()     { return renamesFrom; }
    public String renamesThru()     { return renamesThru; }
    void setRenames(String from, String thru) { this.renamesFrom = from; this.renamesThru = thru; }

    // ---- computed layout
    public int offset()             { return offset; }
    void setOffset(int o)           { this.offset = o; }
    public int length()             { return length; }
    void setLength(int l)           { this.length = l; }

    /**
     * Effective usage: this item's USAGE, else the nearest ancestor's, else DISPLAY.
     * (COBOL allows USAGE on a group to apply to all subordinate elementary items.)
     */
    public Usage effectiveUsage() {
        for (Field f = this; f != null; f = f.parent) {
            if (f.usage != null) return f.usage;
        }
        return Usage.DISPLAY;
    }

    @Override public String toString() {
        return String.format("%02d %-20s pic=%s usage=%s occurs=%s redef=%s off=%d len=%d",
                level, name, rawPicture, usage, (occurs() ? occursMin + ".." + occursMax : "-"),
                redefines, offset, length);
    }
}
