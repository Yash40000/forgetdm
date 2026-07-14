package io.forgetdm.filevault;

import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/** Encrypted managed storage for uploaded mapping assets and generated masking outputs. */
@Component
public class ManagedFileVault {
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int SALT_BYTES = 16;

    private final byte[] masterSecret;
    private final SecureRandom random = new SecureRandom();
    private final String configuredRoot;
    private Path root;

    public ManagedFileVault(ForgeProps props,
                            @Value("${forgetdm.file-vault.root:}") String configuredRoot) {
        String secret = props.getCapsuleSecret();
        if (secret == null || secret.isBlank()) secret = props.getMaskingSecret();
        this.masterSecret = secret.getBytes(StandardCharsets.UTF_8);
        this.configuredRoot = configuredRoot;
    }

    @PostConstruct
    void init() {
        try {
            root = configuredRoot == null || configuredRoot.isBlank()
                    ? Path.of(System.getProperty("java.io.tmpdir"), "forgetdm-file-vault")
                    : Path.of(configuredRoot);
            root = root.toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize ForgeTDM file vault", e);
        }
    }

    public record Stored(String storageKey, String keySalt, String iv, String sha256, long clearSize) {}

    public Stored store(InputStream clear, long declaredSize) {
        if (clear == null) throw ApiException.bad("File content is required");
        String storageKey = UUID.randomUUID() + ".vault";
        String salt = randomHex(SALT_BYTES);
        String iv = randomHex(IV_BYTES);
        Path path = resolve(storageKey);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingInputStream counted = new CountingInputStream(new DigestInputStream(clear, digest));
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, salt, iv);
            try (InputStream in = counted;
                 OutputStream raw = Files.newOutputStream(path);
                 CipherOutputStream encrypted = new CipherOutputStream(raw, cipher)) {
                in.transferTo(encrypted);
            }
            if (declaredSize >= 0 && counted.count() != declaredSize) {
                Files.deleteIfExists(path);
                throw ApiException.bad("Uploaded file size changed while it was being stored");
            }
            return new Stored(storageKey, salt, iv, HexFormat.of().formatHex(digest.digest()), counted.count());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            try { Files.deleteIfExists(path); } catch (IOException ignore) { }
            throw ApiException.bad("Could not store encrypted file: " + e.getMessage());
        }
    }

    public OutputHandle createOutput() {
        String storageKey = UUID.randomUUID() + ".vault";
        String salt = randomHex(SALT_BYTES);
        String iv = randomHex(IV_BYTES);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingOutputStream counted = new CountingOutputStream(new DigestOutputStream(
                    new CipherOutputStream(Files.newOutputStream(resolve(storageKey)),
                            cipher(Cipher.ENCRYPT_MODE, salt, iv)), digest));
            return new OutputHandle(storageKey, salt, iv, digest, counted);
        } catch (Exception e) {
            throw ApiException.bad("Could not create encrypted output: " + e.getMessage());
        }
    }

    public InputStream open(String storageKey, String salt, String iv) {
        try {
            return new CipherInputStream(Files.newInputStream(resolve(storageKey)),
                    cipher(Cipher.DECRYPT_MODE, salt, iv));
        } catch (Exception e) {
            throw ApiException.bad("Could not open encrypted file: " + e.getMessage());
        }
    }

    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return;
        try { Files.deleteIfExists(resolve(storageKey)); }
        catch (IOException e) { throw ApiException.bad("Could not remove managed file: " + e.getMessage()); }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || !storageKey.matches("[A-Za-z0-9._-]{10,120}"))
            throw ApiException.bad("Invalid managed file key");
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) throw ApiException.bad("Invalid managed file path");
        return path;
    }

    private Cipher cipher(int mode, String saltHex, String ivHex) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(masterSecret);
        md.update((byte) 0);
        md.update(HexFormat.of().parseHex(saltHex));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(md.digest(), "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, HexFormat.of().parseHex(ivHex)));
        return cipher;
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    public static final class OutputHandle implements AutoCloseable {
        private final String storageKey;
        private final String keySalt;
        private final String iv;
        private final MessageDigest digest;
        private final CountingOutputStream out;
        private boolean closed;

        private OutputHandle(String storageKey, String keySalt, String iv,
                             MessageDigest digest, CountingOutputStream out) {
            this.storageKey = storageKey; this.keySalt = keySalt; this.iv = iv;
            this.digest = digest; this.out = out;
        }

        public OutputStream stream() { return out; }
        public String storageKey() { return storageKey; }
        public String keySalt() { return keySalt; }
        public String iv() { return iv; }
        public long clearSize() { return out.count(); }
        public String sha256() {
            if (!closed) throw new IllegalStateException("Close the output before reading its digest");
            return HexFormat.of().formatHex(digest.digest());
        }
        @Override public void close() throws IOException { if (!closed) { closed = true; out.close(); } }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;
        private CountingInputStream(InputStream in) { super(in); }
        @Override public int read() throws IOException { int v = super.read(); if (v >= 0) count++; return v; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len); if (n > 0) count += n; return n;
        }
        long count() { return count; }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;
        private CountingOutputStream(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
        long count() { return count; }
    }
}
