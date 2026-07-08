package io.forgetdm.core.copybook.codec;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * EBCDIC text codec for alphanumeric / alphabetic / edited fields.
 *
 * The code page is configurable per copybook/dataset because shops differ:
 *   Cp037  US/Canada (default)     Cp500  International       Cp1140  US with euro
 *   Cp273  Germany                 Cp297  France             Cp277  Denmark/Norway ...
 *
 * Numerics (zoned/packed/binary) are NOT routed through here — they are decoded from raw
 * nibbles/bytes so they are independent of the text code page.
 */
public final class Ebcdic {

    public static final String DEFAULT_CODE_PAGE = "Cp037";

    private final Charset charset;
    private final byte spaceByte;   // the code page's encoding of ' ' (0x40 in every EBCDIC page)

    public Ebcdic(String codePage) {
        try {
            this.charset = Charset.forName(codePage == null || codePage.isBlank() ? DEFAULT_CODE_PAGE : codePage);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown EBCDIC code page '" + codePage + "': " + e.getMessage());
        }
        byte[] sp = " ".getBytes(charset);
        this.spaceByte = sp.length > 0 ? sp[0] : 0x40;
    }

    public static Ebcdic defaultPage() { return new Ebcdic(DEFAULT_CODE_PAGE); }

    public Charset charset() { return charset; }

    /** Decode {@code len} bytes at {@code off} to a String (trailing spaces preserved as-is). */
    public String decode(byte[] data, int off, int len) {
        return new String(data, off, len, charset);
    }

    /**
     * Encode {@code value} into exactly {@code len} bytes, EBCDIC, left-justified and
     * space-padded on the right (standard COBOL alphanumeric semantics). Over-long input is
     * truncated to {@code len} bytes.
     */
    public byte[] encode(String value, int len) {
        byte[] out = new byte[len];
        Arrays.fill(out, spaceByte);
        if (value == null) return out;
        byte[] enc = value.getBytes(charset);
        int n = Math.min(enc.length, len);
        System.arraycopy(enc, 0, out, 0, n);
        return out;
    }
}
