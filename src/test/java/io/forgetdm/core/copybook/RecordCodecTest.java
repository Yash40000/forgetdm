package io.forgetdm.core.copybook;

import io.forgetdm.core.copybook.codec.BinaryInt;
import io.forgetdm.core.copybook.codec.Ebcdic;
import io.forgetdm.core.copybook.codec.PackedDecimal;
import io.forgetdm.core.copybook.codec.ZonedDecimal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end: build EBCDIC records, decode, mask via overlay, and assert byte fidelity. */
class RecordCodecTest {

    private static final Ebcdic E = Ebcdic.defaultPage();

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.writeBytes(p);
        return b.toByteArray();
    }

    private static byte[] zoned(String v, int len) { return ZonedDecimal.encode(new BigDecimal(v), len, 0, false, false, false); }

    // -------------------------------------------------------------- full record + masking

    @Test void decodeMaskAndReencodePreservesBytes() {
        String cpy = String.join("\n",
                "01 CUSTOMER-RECORD.",
                "   05 CUST-ID      PIC 9(5).",
                "   05 CUST-NAME    PIC X(10).",
                "   05 BALANCE      PIC S9(5)V99 COMP-3.",
                "   05 ACCT-COUNT   PIC 9(2) COMP.",
                "   05 STATUS-FLAG  PIC X.");
        Copybook cb = CopybookParser.parse(cpy);
        RecordCodec codec = new RecordCodec(cb.primaryRecord(), E);

        byte[] rec = concat(
                zoned("12345", 5),
                E.encode("ALICE", 10),
                PackedDecimal.encode(new BigDecimal("-123.45"), 4, 2, true),
                BinaryInt.encode(new BigDecimal("7"), 2, 0, false),
                E.encode("A", 1));
        assertEquals(22, rec.length);

        RecordValue rv = codec.decode(rec);
        assertEquals("12345", rv.get("CUST-ID").value());
        assertEquals("ALICE", rv.get("CUST-NAME").value().trim());
        assertEquals("-123.45", rv.get("BALANCE").value());
        assertEquals("7", rv.get("ACCT-COUNT").value());
        assertEquals("A", rv.get("STATUS-FLAG").value());
        assertEquals(15, rv.get("BALANCE").offset());

        // no changes → identical bytes (the core fidelity guarantee)
        assertArrayEquals(rec, codec.encodeOverlay(rv, rec, Map.of()));

        // mask two fields; everything else must be byte-identical
        byte[] masked = codec.encodeOverlay(rv, rec, Map.of("CUST-NAME", "BOB", "BALANCE", "999.99"));
        assertEquals(rec.length, masked.length);
        RecordValue rv2 = codec.decode(masked);
        assertEquals("BOB", rv2.get("CUST-NAME").value().trim());
        assertEquals("999.99", rv2.get("BALANCE").value());
        assertEquals("12345", rv2.get("CUST-ID").value());
        assertEquals("7", rv2.get("ACCT-COUNT").value());
        assertEquals("A", rv2.get("STATUS-FLAG").value());
        for (int i = 0; i < 5; i++) assertEquals(rec[i], masked[i], "CUST-ID byte " + i);
        for (int i = 19; i < 22; i++) assertEquals(rec[i], masked[i], "tail byte " + i);
    }

    // -------------------------------------------------------------- OCCURS

    @Test void decodesFixedOccursWithSubscripts() {
        String cpy = String.join("\n",
                "01 ORDER-REC.",
                "   05 ITEM-COUNT  PIC 9(2).",
                "   05 ITEM OCCURS 3 TIMES.",
                "      10 SKU  PIC X(3).",
                "      10 QTY  PIC 9(2) COMP-3.");
        Copybook cb = CopybookParser.parse(cpy);
        RecordCodec codec = new RecordCodec(cb.primaryRecord(), E);

        byte[] rec = concat(
                zoned("3", 2),
                E.encode("ABC", 3), PackedDecimal.encode(new BigDecimal("10"), 2, 0, true),
                E.encode("DEF", 3), PackedDecimal.encode(new BigDecimal("20"), 2, 0, true),
                E.encode("GHI", 3), PackedDecimal.encode(new BigDecimal("30"), 2, 0, true));
        assertEquals(17, rec.length);

        RecordValue rv = codec.decode(rec);
        assertEquals("ABC", rv.get("ITEM(1).SKU").value().trim());
        assertEquals("20", rv.get("ITEM(2).QTY").value());
        assertEquals("GHI", rv.get("ITEM(3).SKU").value().trim());
        assertFalse(rv.has("ITEM(4).SKU"));
    }

    // -------------------------------------------------------------- OCCURS DEPENDING ON

    @Test void decodesOccursDependingOnCount() {
        String cpy = String.join("\n",
                "01 ODO.",
                "   05 N PIC 9(1).",
                "   05 E OCCURS 1 TO 5 DEPENDING ON N.",
                "      10 V PIC 9(2).");
        Copybook cb = CopybookParser.parse(cpy);
        RecordCodec codec = new RecordCodec(cb.primaryRecord(), E);

        // N=3 → only three occurrences are present in the physical record (7 bytes, not the max 11)
        byte[] rec = concat(zoned("3", 1), zoned("11", 2), zoned("22", 2), zoned("33", 2));
        assertEquals(7, rec.length);

        RecordValue rv = codec.decode(rec);
        assertEquals("3", rv.get("N").value());
        assertEquals("11", rv.get("E(1).V").value());
        assertEquals("33", rv.get("E(3).V").value());
        assertTrue(rv.has("E(3).V"));
        assertFalse(rv.has("E(4).V"), "must stop at the depending-on count");
    }

    // -------------------------------------------------------------- REDEFINES

    @Test void decodesBothRedefineViews() {
        String cpy = String.join("\n",
                "01 R.",
                "   05 A PIC X(4).",
                "   05 B REDEFINES A PIC 9(4).");
        Copybook cb = CopybookParser.parse(cpy);
        RecordCodec codec = new RecordCodec(cb.primaryRecord(), E);

        byte[] rec = zoned("1234", 4);   // F1 F2 F3 F4 — valid as text AND as zoned digits
        RecordValue rv = codec.decode(rec);
        assertEquals("1234", rv.get("A").value());   // EBCDIC text view
        assertEquals("1234", rv.get("B").value());   // numeric overlay view
        assertEquals(rv.get("A").offset(), rv.get("B").offset());
    }
}
