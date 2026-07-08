package io.forgetdm.core.copybook;

import io.forgetdm.core.copybook.codec.BinaryInt;
import io.forgetdm.core.copybook.codec.Ebcdic;
import io.forgetdm.core.copybook.codec.PackedDecimal;
import io.forgetdm.core.copybook.codec.ZonedDecimal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Absolute-value byte fixtures + round-trip for the four low-level codecs and the PICTURE parser. */
class CopybookCodecTest {

    private static byte b(int v) { return (byte) v; }

    // -------------------------------------------------------------- packed (COMP-3)

    @Test void packedEncodesKnownHex() {
        // +12345, S9(5) COMP-3 → 12 34 5C   (sign nibble C = positive)
        assertArrayEquals(new byte[]{b(0x12), b(0x34), b(0x5C)},
                PackedDecimal.encode(new BigDecimal("12345"), 3, 0, true));
        // -12345 → 12 34 5D   (sign nibble D = negative)
        assertArrayEquals(new byte[]{b(0x12), b(0x34), b(0x5D)},
                PackedDecimal.encode(new BigDecimal("-12345"), 3, 0, true));
        // even digit count S9(4): leading unused zero nibble → 01 23 4C
        assertArrayEquals(new byte[]{b(0x01), b(0x23), b(0x4C)},
                PackedDecimal.encode(new BigDecimal("1234"), 3, 0, true));
        // unsigned uses F sign nibble
        assertArrayEquals(new byte[]{b(0x12), b(0x34), b(0x5F)},
                PackedDecimal.encode(new BigDecimal("12345"), 3, 0, false));
    }

    @Test void packedByteLengthAndRoundTrip() {
        assertEquals(3, PackedDecimal.byteLength(5));
        assertEquals(3, PackedDecimal.byteLength(4));
        assertEquals(5, PackedDecimal.byteLength(9));
        for (String v : new String[]{"0", "7", "-7", "999999999", "-123.45", "123.45"}) {
            int scale = v.contains(".") ? 2 : 0;
            int digits = v.replace("-", "").replace(".", "").length();
            int len = PackedDecimal.byteLength(Math.max(digits, 1));
            byte[] enc = PackedDecimal.encode(new BigDecimal(v), len, scale, true);
            assertEquals(new BigDecimal(v), PackedDecimal.decode(enc, 0, len, scale), "round-trip " + v);
        }
    }

    // -------------------------------------------------------------- binary (COMP)

    @Test void binaryEncodesKnownHex() {
        assertArrayEquals(new byte[]{b(0x00), b(0x01)}, BinaryInt.encode(new BigDecimal("1"), 2, 0, true));
        assertArrayEquals(new byte[]{b(0xFF), b(0xFF)}, BinaryInt.encode(new BigDecimal("-1"), 2, 0, true));
        assertArrayEquals(new byte[]{b(0x01), b(0x02)}, BinaryInt.encode(new BigDecimal("258"), 2, 0, true));
        assertArrayEquals(new byte[]{b(0xFF), b(0xFF)}, BinaryInt.encode(new BigDecimal("65535"), 2, 0, false));
    }

    @Test void binaryByteWidthsAndSignedness() {
        assertEquals(2, BinaryInt.byteLength(4));
        assertEquals(4, BinaryInt.byteLength(9));
        assertEquals(8, BinaryInt.byteLength(18));
        byte[] ff = {b(0xFF), b(0xFF)};
        assertEquals(new BigDecimal("-1"), BinaryInt.decode(ff, 0, 2, 0, true));
        assertEquals(new BigDecimal("65535"), BinaryInt.decode(ff, 0, 2, 0, false));
        // overflow guard
        assertThrows(RuntimeException.class, () -> BinaryInt.encode(new BigDecimal("40000"), 2, 0, true));
    }

    // -------------------------------------------------------------- zoned (DISPLAY numeric)

    @Test void zonedEncodesKnownHex() {
        assertArrayEquals(new byte[]{b(0xF1), b(0xF2), b(0xF3)},
                ZonedDecimal.encode(new BigDecimal("123"), 3, 0, false, false, false));
        // signed negative trailing overpunch: last byte zone D
        assertArrayEquals(new byte[]{b(0xF1), b(0xF2), b(0xD3)},
                ZonedDecimal.encode(new BigDecimal("-123"), 3, 0, true, false, false));
        // signed positive trailing overpunch: last byte zone C
        assertArrayEquals(new byte[]{b(0xF1), b(0xF2), b(0xC3)},
                ZonedDecimal.encode(new BigDecimal("123"), 3, 0, true, false, false));
    }

    @Test void zonedRoundTripIncludingSeparateSign() {
        // overpunch
        for (String v : new String[]{"0", "5", "-5", "123", "-123"}) {
            byte[] enc = ZonedDecimal.encode(new BigDecimal(v), 3, 0, true, false, false);
            assertEquals(new BigDecimal(v), ZonedDecimal.decode(enc, 0, 3, 0, true, false, false), "overpunch " + v);
        }
        // SIGN LEADING SEPARATE: extra leading byte
        byte[] enc = ZonedDecimal.encode(new BigDecimal("-45"), 3, 0, true, true, true);
        assertEquals(b(0x60), enc[0]); // EBCDIC '-'
        assertEquals(new BigDecimal("-45"), ZonedDecimal.decode(enc, 0, 3, 0, true, true, true));
        // scaled
        byte[] s = ZonedDecimal.encode(new BigDecimal("12.34"), 4, 2, false, false, false);
        assertEquals(new BigDecimal("12.34"), ZonedDecimal.decode(s, 0, 4, 2, false, false, false));
    }

    // -------------------------------------------------------------- EBCDIC text

    @Test void ebcdicRoundTripAndPadding() {
        Ebcdic e = Ebcdic.defaultPage();
        byte[] enc = e.encode("ALICE", 10);
        assertEquals(10, enc.length);
        assertEquals(b(0x40), enc[9]);                 // EBCDIC space pad
        assertEquals("ALICE", e.decode(enc, 0, 10).trim());
        // 'A' is 0xC1 in Cp037
        assertEquals(b(0xC1), e.encode("A", 1)[0]);
    }

    // -------------------------------------------------------------- PICTURE parsing

    @Test void pictureParsesDigitsScaleSignCategory() {
        Picture p = Picture.parse("S9(5)V99");
        assertEquals(7, p.digits());
        assertEquals(2, p.scale());
        assertTrue(p.signed());
        assertEquals(Picture.Category.NUMERIC, p.category());

        Picture x = Picture.parse("X(10)");
        assertEquals(Picture.Category.ALPHANUMERIC, x.category());
        assertEquals(10, x.displayLength());

        Picture d = Picture.parse("9(3)V9");
        assertEquals(4, d.digits());
        assertEquals(1, d.scale());
        assertFalse(d.signed());
    }
}
