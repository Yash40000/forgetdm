package io.forgetdm.core.copybook.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Packed decimal (COMP-3 / PACKED-DECIMAL) codec.
 *
 * Layout: two decimal digits per byte, most-significant first, with the final low nibble
 * holding the sign. A field of N declared digits occupies ceil((N+1)/2) bytes; when N is even
 * the leading nibble is an unused zero.
 *
 * Sign nibbles:  C, A, E, F → positive ;  D, B → negative ;  F → unsigned/positive.
 * We emit C for signed-positive, D for negative, F for unsigned.
 */
public final class PackedDecimal {

    private PackedDecimal() {}

    /** Bytes needed to store {@code digits} packed digits plus the sign nibble. */
    public static int byteLength(int digits) {
        return (digits / 2) + 1;   // == ceil((digits+1)/2)
    }

    public static BigDecimal decode(byte[] data, int off, int len, int scale) {
        if (len <= 0) throw new IllegalArgumentException("Packed field length must be positive");
        int nibbleCount = len * 2;
        StringBuilder digits = new StringBuilder(nibbleCount);
        int signNibble = data[off + len - 1] & 0x0F;
        for (int n = 0; n < nibbleCount - 1; n++) {           // every nibble except the last (sign)
            int b = data[off + (n / 2)] & 0xFF;
            int nib = (n % 2 == 0) ? (b >>> 4) & 0x0F : b & 0x0F;
            digits.append((char) ('0' + (nib > 9 ? 0 : nib)));  // tolerate stray non-digit nibbles as 0
        }
        BigInteger unscaled = new BigInteger(digits.length() == 0 ? "0" : digits.toString());
        if (signNibble == 0x0D || signNibble == 0x0B) unscaled = unscaled.negate();
        return new BigDecimal(unscaled, scale);
    }

    public static byte[] encode(BigDecimal value, int len, int scale, boolean signed) {
        if (len <= 0) throw new IllegalArgumentException("Packed field length must be positive");
        BigDecimal scaled = value.setScale(scale, RoundingMode.HALF_UP);
        boolean negative = scaled.signum() < 0;
        String digitStr = scaled.unscaledValue().abs().toString();

        int digitNibbles = len * 2 - 1;
        if (digitStr.length() > digitNibbles) {
            throw new IllegalArgumentException("Packed value " + value + " needs " + digitStr.length()
                    + " digits but the field holds only " + digitNibbles);
        }
        // left-pad with zeros to fill the digit nibbles
        StringBuilder nibbles = new StringBuilder(len * 2);
        for (int i = 0; i < digitNibbles - digitStr.length(); i++) nibbles.append('0');
        nibbles.append(digitStr);
        // sign nibble
        char sign = negative ? 'D' : (signed ? 'C' : 'F');
        nibbles.append(sign);

        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = nibbleVal(nibbles.charAt(2 * i));
            int lo = nibbleVal(nibbles.charAt(2 * i + 1));
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int nibbleVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        switch (c) { case 'C': return 0xC; case 'D': return 0xD; case 'F': return 0xF; default: return 0; }
    }
}
