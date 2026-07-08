package io.forgetdm.core.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Deterministic, irreversible value derivation — the cryptographic spine of ForgeTDM.
 *
 * Design (best-of-breed synthesis):
 *  - Hash-based consistency        -> same input + same secret => same output, forever, across engines.
 *  - Entity-consistent masking     -> no shared XREF table needed; keyed HMAC is stateless & horizontally scalable.
 *  - Irreversibility (masking != encryption) -> HMAC-SHA256, key never stored with data.
 */
public final class Determinism {
    private Determinism() {}

    public static byte[] hmac(String secret, String salt, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(salt.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0x1f);
            return mac.doFinal(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Non-negative deterministic long for (secret, salt, value). */
    public static long hashLong(String secret, String salt, String value) {
        byte[] h = hmac(secret, salt, value);
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xff);
        return v & Long.MAX_VALUE;
    }

    /** Deterministic PRNG seeded from the value — for multi-draw functions (format preserve etc.). */
    public static Random rng(String secret, String salt, String value) {
        return new Random(hashLong(secret, salt, value));
    }

    /** Deterministic index into a list of given size. */
    public static int pick(String secret, String salt, String value, int size) {
        if (size <= 0) throw new IllegalArgumentException("empty seedlist");
        return (int) (hashLong(secret, salt, value) % size);
    }
}
