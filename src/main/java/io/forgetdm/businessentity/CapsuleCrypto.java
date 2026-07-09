package io.forgetdm.businessentity;

import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Envelope-style encryption for Micro-DB capsule fragment payloads (K2View encrypts each
 * micro-database with its own key; we do the same logically):
 *
 *   master secret (config, forgetdm.capsule-secret)
 *     + per-instance random key salt (be_entity_instances.key_salt)
 *     --SHA-256--> per-capsule AES-256 key
 *   per-fragment random 96-bit IV (be_entity_fragments.payload_iv), AES-256-GCM.
 *
 * A leaked control-DB dump therefore exposes no fragment payloads without the master secret,
 * and no two capsules share an encryption key.
 */
@Component
public class CapsuleCrypto {
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int SALT_BYTES = 16;

    private final byte[] masterSecret;
    private final SecureRandom random = new SecureRandom();

    public CapsuleCrypto(ForgeProps props) {
        String secret = props.getCapsuleSecret();
        if (secret == null || secret.isBlank()) secret = props.getMaskingSecret();
        this.masterSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** New random per-instance salt (hex). */
    public String newKeySalt() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    public record Encrypted(String cipherTextBase64, String ivHex) {}

    public Encrypted encrypt(String clearJson, String keySaltHex) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(keySaltHex), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(clearJson.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(cipherText), HexFormat.of().formatHex(iv));
        } catch (Exception e) {
            throw ApiException.bad("Capsule payload encryption failed: " + e.getMessage());
        }
    }

    public String decrypt(String cipherTextBase64, String ivHex, String keySaltHex) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(keySaltHex),
                    new GCMParameterSpec(GCM_TAG_BITS, HexFormat.of().parseHex(ivHex)));
            byte[] clear = cipher.doFinal(Base64.getDecoder().decode(cipherTextBase64));
            return new String(clear, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw ApiException.bad("Capsule payload decryption failed (wrong master secret?): " + e.getMessage());
        }
    }

    private SecretKeySpec key(String keySaltHex) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(masterSecret);
        md.update((byte) 0);
        md.update(HexFormat.of().parseHex(keySaltHex == null ? "" : keySaltHex));
        return new SecretKeySpec(md.digest(), "AES");
    }
}
