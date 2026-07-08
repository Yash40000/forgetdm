package io.forgetdm.core.copybook.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * Binary (COMP / COMP-4 / BINARY / COMP-5) codec — big-endian, two's-complement for signed
 * items, magnitude for unsigned.
 *
 * Standard IBM byte widths by declared digit count:
 *   1–4 digits → 2 bytes (halfword)
 *   5–9 digits → 4 bytes (fullword)
 *  10–18 digits → 8 bytes (doubleword)
 */
public final class BinaryInt {

    private BinaryInt() {}

    public static int byteLength(int digits) {
        if (digits <= 4)  return 2;
        if (digits <= 9)  return 4;
        if (digits <= 18) return 8;
        throw new IllegalArgumentException("Binary item with " + digits + " digits exceeds 18-digit COBOL limit");
    }

    public static BigDecimal decode(byte[] data, int off, int len, int scale, boolean signed) {
        byte[] slice = Arrays.copyOfRange(data, off, off + len);
        BigInteger bi = signed ? new BigInteger(slice) : new BigInteger(1, slice);
        return new BigDecimal(bi, scale);
    }

    public static byte[] encode(BigDecimal value, int len, int scale, boolean signed) {
        BigInteger unscaled = value.setScale(scale, RoundingMode.HALF_UP).unscaledValue();
        return toFixedWidth(unscaled, len, signed);
    }

    static byte[] toFixedWidth(BigInteger v, int len, boolean signed) {
        if (!signed && v.signum() < 0) throw new IllegalArgumentException("Unsigned binary cannot hold negative " + v);
        // range check so a high bit never silently flips the sign
        if (signed) {
            BigInteger max = BigInteger.ONE.shiftLeft(len * 8 - 1).subtract(BigInteger.ONE);
            BigInteger min = max.add(BigInteger.ONE).negate();
            if (v.compareTo(max) > 0 || v.compareTo(min) < 0)
                throw new IllegalArgumentException("Value " + v + " does not fit in a signed " + len + "-byte binary field");
        } else {
            BigInteger max = BigInteger.ONE.shiftLeft(len * 8).subtract(BigInteger.ONE);
            if (v.compareTo(max) > 0)
                throw new IllegalArgumentException("Value " + v + " does not fit in an unsigned " + len + "-byte binary field");
        }
        byte[] out = new byte[len];
        byte sign = (byte) (v.signum() < 0 ? 0xFF : 0x00);
        Arrays.fill(out, sign);
        byte[] full = v.toByteArray();   // minimal big-endian two's complement
        int fi = full.length - 1, oi = len - 1;
        while (fi >= 0 && oi >= 0) out[oi--] = full[fi--];
        return out;
    }
}
