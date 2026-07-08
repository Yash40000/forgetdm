package io.forgetdm.core.copybook;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Parser + LayoutComputer: offsets, lengths, OCCURS, REDEFINES, RENAMES, USAGE inheritance, ODO. */
class CopybookParserTest {

    private static Field f(Copybook cb, String name) {
        Map<String, Field> idx = Copybook.nameIndex(cb.primaryRecord());
        Field field = idx.get(name.toUpperCase());
        assertNotNull(field, "field " + name + " should exist");
        return field;
    }

    @Test void computesOffsetsAndLengthsAcrossUsages() {
        String cpy = String.join("\n",
                "01 CUSTOMER-RECORD.",
                "   05 CUST-ID      PIC 9(5).",
                "   05 CUST-NAME    PIC X(10).",
                "   05 BALANCE      PIC S9(5)V99 COMP-3.",
                "   05 ACCT-COUNT   PIC 9(2) COMP.",
                "   05 STATUS-FLAG  PIC X.");
        Copybook cb = CopybookParser.parse(cpy);

        assertEquals(0, f(cb, "CUST-ID").offset());      assertEquals(5, f(cb, "CUST-ID").length());
        assertEquals(5, f(cb, "CUST-NAME").offset());    assertEquals(10, f(cb, "CUST-NAME").length());
        assertEquals(15, f(cb, "BALANCE").offset());     assertEquals(4, f(cb, "BALANCE").length());   // packed S9(5)V99
        assertEquals(19, f(cb, "ACCT-COUNT").offset());  assertEquals(2, f(cb, "ACCT-COUNT").length()); // binary 9(2)
        assertEquals(21, f(cb, "STATUS-FLAG").offset()); assertEquals(1, f(cb, "STATUS-FLAG").length());
        assertEquals(22, cb.primaryRecord().length());
    }

    @Test void handlesFixedOccursTable() {
        String cpy = String.join("\n",
                "01 ORDER-REC.",
                "   05 ITEM-COUNT  PIC 9(2).",
                "   05 ITEM OCCURS 3 TIMES.",
                "      10 SKU  PIC X(3).",
                "      10 QTY  PIC 9(2) COMP-3.");
        Copybook cb = CopybookParser.parse(cpy);

        Field item = f(cb, "ITEM");
        assertEquals(3, item.occursMax());
        assertEquals(5, item.length());                 // single occurrence: 3 + 2
        assertEquals(2, item.offset());
        assertEquals(5, f(cb, "QTY").offset());          // first occurrence position
        assertEquals(17, cb.primaryRecord().length());   // 2 + 5*3
    }

    @Test void redefinesOverlaysSameOffset() {
        String cpy = String.join("\n",
                "01 R.",
                "   05 A PIC X(4).",
                "   05 B REDEFINES A PIC 9(4).");
        Copybook cb = CopybookParser.parse(cpy);
        assertEquals(0, f(cb, "A").offset());
        assertEquals(0, f(cb, "B").offset());           // overlaid at A
        assertEquals(4, f(cb, "B").length());
        assertEquals(4, cb.primaryRecord().length());   // redefine adds no storage
    }

    @Test void renamesResolveToByteRange() {
        String cpy = String.join("\n",
                "01 N.",
                "   05 FNAME PIC X(5).",
                "   05 LNAME PIC X(5).",
                "   66 FULL-NAME RENAMES FNAME THRU LNAME.");
        Copybook cb = CopybookParser.parse(cpy);
        Field full = f(cb, "FULL-NAME");
        assertTrue(full.isRename());
        assertEquals(0, full.offset());
        assertEquals(10, full.length());
        assertEquals(10, cb.primaryRecord().length());  // rename is not storage
    }

    @Test void usageInheritsFromGroup() {
        String cpy = String.join("\n",
                "01 G.",
                "   05 GRP USAGE COMP-3.",
                "      10 A PIC 9(3).",
                "      10 B PIC 9(5).");
        Copybook cb = CopybookParser.parse(cpy);
        assertEquals(Usage.COMP3, f(cb, "A").effectiveUsage());
        assertEquals(2, f(cb, "A").length());           // packed 3 digits → 2 bytes
        assertEquals(3, f(cb, "B").length());           // packed 5 digits → 3 bytes
        assertEquals(5, cb.primaryRecord().length());
    }

    @Test void parsesOccursDependingOn() {
        String cpy = String.join("\n",
                "01 ODO.",
                "   05 N PIC 9(1).",
                "   05 E OCCURS 1 TO 5 DEPENDING ON N.",
                "      10 V PIC 9(2).");
        Copybook cb = CopybookParser.parse(cpy);
        Field e = f(cb, "E");
        assertEquals("N", e.dependingOn());
        assertEquals(1, e.occursMin());
        assertEquals(5, e.occursMax());
    }
}
