package io.forgetdm.core.copybook.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Zoned decimal codec — the storage form of DISPLAY numeric items (PIC 9).
 *
 * One byte per digit. In EBCDIC a digit byte is 0xF0–0xF9 (zone nibble 0xF, digit in the low
 * nibble). For signed items the sign is "overpunched" into the zone nibble of the sign position
 * (trailing digit by default, leading digit under SIGN LEADING):
 *     positive → zone C (also A/E/F)      negative → zone D (also B)
 *
 * With SIGN SEPARATE an extra byte holds an EBCDIC '+' (0x4E) or '-' (0x60), leading or trailing.
 *
 * Digit bytes are handled at the nibble level, so this codec is independent of the text code page.
 */
public final class ZonedDecimal {

    private ZonedDecimal() {}

    private static final byte PLUS  = 0x4E;   // EBCDIC '+'
    private static final byte MINUS = 0x60;   // EBCDIC '-'

    public static BigDecimal decode(byte[] data, int off, int len, int scale,
                                    boolean signed, boolean signLeading, boolean signSeparate) {
        boolean negative;
        int digitsStart, digitsLen;

        if (signSeparate) {
            digitsLen = len - 1;
            if (signLeading) {
                negative = (data[off] == MINUS);
                digitsStart = off + 1;
            } else {
                negative = (data[off + len - 1] == MINUS);
                digitsStart = off;
            }
        } else {
            digitsStart = off;
            digitsLen = len;
            int signIdx = signLeading ? off : off + len - 1;
            int zone = (data[signIdx] >>> 4) & 0x0F;
            negative = signed && (zone == 0x0D || zone == 0x0B);
        }

        StringBuilder sb = new StringBuilder(digitsLen);
        for (int i = 0; i < digitsLen; i++) sb.append((char) ('0' + (data[digitsStart + i] & 0x0F)));
        BigInteger unscaled = new BigInteger(sb.length() == 0 ? "0" : sb.toString());
        if (negative) unscaled = unscaled.negate();
        return new BigDecimal(unscaled, scale);
    }

    public static byte[] encode(BigDecimal value, int len, int scale,
                                boolean signed, boolean signLeading, boolean signSeparate) {
        BigDecimal scaled = value.setScale(scale, RoundingMode.HALF_UP);
        boolean negative = scaled.signum() < 0;
        String digitStr = scaled.unscaledValue().abs().toString();

        int digitCount = signSeparate ? len - 1 : len;
        if (digitStr.length() > digitCount)
            throw new IllegalArgumentException("Zoned value " + value + " needs " + digitStr.length()
                    + " digits but the field holds only " + digitCount);
        // left-pad
        StringBuilder digits = new StringBuilder(digitCount);
        for (int i = 0; i < digitCount - digitStr.length(); i++) digits.append('0');
        digits.append(digitStr);

        byte[] out = new byte[len];
        if (signSeparate) {
            int digitsStart = signLeading ? 1 : 0;
            for (int i = 0; i < digitCount; i++)
                out[digitsStart + i] = (byte) (0xF0 | (digits.charAt(i) - '0'));
            byte signByte = negative ? MINUS : PLUS;
            out[signLeading ? 0 : len - 1] = signByte;
        } else {
            for (int i = 0; i < digitCount; i++)
                out[i] = (byte) (0xF0 | (digits.charAt(i) - '0'));
            int signIdx = signLeading ? 0 : len - 1;
            int zone = signed ? (negative ? 0xD0 : 0xC0) : 0xF0;
            out[signIdx] = (byte) (zone | (digits.charAt(signIdx == 0 ? 0 : digitCount - 1) - '0'));
        }
        return out;
    }
}
