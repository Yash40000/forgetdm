package io.forgetdm.mainframe;

import io.forgetdm.common.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a raw dataset image into logical records and rejoins them, per RECFM.
 *
 *  F / FB — fixed: every record is exactly LRECL bytes.
 *  V / VB — variable: each record is preceded by a 4-byte Record Descriptor Word (RDW): a 2-byte
 *           big-endian length that INCLUDES the RDW, then two reserved zero bytes. split() returns
 *           the data portion (RDW stripped); join() re-prepends a correct RDW.
 *
 * Masking is length-preserving, so rejoining is exact.
 */
public final class RecordSplitter {

    private RecordSplitter() {}

    public static boolean isVariable(String recfm) {
        return recfm != null && recfm.trim().toUpperCase(Locale.ROOT).startsWith("V");
    }

    public static List<byte[]> split(byte[] data, String recfm, int lrecl) {
        return isVariable(recfm) ? splitVB(data) : splitFB(data, lrecl);
    }

    public static byte[] join(List<byte[]> records, String recfm) {
        return isVariable(recfm) ? joinVB(records) : joinFB(records);
    }

    private static List<byte[]> splitFB(byte[] data, int lrecl) {
        if (lrecl <= 0) throw ApiException.bad("FB record format requires a positive LRECL / record length");
        if (data.length % lrecl != 0)
            throw ApiException.bad("File length " + data.length + " is not a multiple of LRECL " + lrecl
                    + " — wrong copybook/LRECL, or the transfer was not binary");
        List<byte[]> out = new ArrayList<>(data.length / lrecl);
        for (int i = 0; i < data.length; i += lrecl) {
            byte[] rec = new byte[lrecl];
            System.arraycopy(data, i, rec, 0, lrecl);
            out.add(rec);
        }
        return out;
    }

    private static byte[] joinFB(List<byte[]> records) {
        int total = records.stream().mapToInt(r -> r.length).sum();
        byte[] out = new byte[total];
        int p = 0;
        for (byte[] r : records) { System.arraycopy(r, 0, out, p, r.length); p += r.length; }
        return out;
    }

    private static List<byte[]> splitVB(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (i + 4 <= data.length) {
            int rdw = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);   // length incl. the 4-byte RDW
            if (rdw < 4) throw ApiException.bad("Invalid RDW length " + rdw + " at offset " + i);
            int dataLen = rdw - 4;
            if (i + 4 + dataLen > data.length)
                throw ApiException.bad("Truncated VB record at offset " + i + " (RDW says " + rdw + ")");
            byte[] rec = new byte[dataLen];
            System.arraycopy(data, i + 4, rec, 0, dataLen);
            out.add(rec);
            i += rdw;
        }
        if (i != data.length)
            throw ApiException.bad("Trailing " + (data.length - i) + " bytes are not a complete VB record");
        return out;
    }

    private static byte[] joinVB(List<byte[]> records) {
        int total = records.stream().mapToInt(r -> r.length + 4).sum();
        byte[] out = new byte[total];
        int p = 0;
        for (byte[] r : records) {
            int rdw = r.length + 4;
            out[p]     = (byte) ((rdw >>> 8) & 0xFF);
            out[p + 1] = (byte) (rdw & 0xFF);
            out[p + 2] = 0;
            out[p + 3] = 0;
            System.arraycopy(r, 0, out, p + 4, r.length);
            p += rdw;
        }
        return out;
    }
}
