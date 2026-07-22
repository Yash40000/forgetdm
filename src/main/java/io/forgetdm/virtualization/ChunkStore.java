package io.forgetdm.virtualization;

import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.vault.MaskingSecretResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The "storage pool": a content-addressed, deduplicated, compressed chunk store.
 *
 * Every chunk (a batch of serialized rows, a table manifest, a snapshot manifest)
 * is keyed by the SHA-256 of its uncompressed content and stored compressed at
 * pool/chunks/&lt;aa&gt;/&lt;hash&gt;.gz. When encrypted staging is enabled, the compressed bytes
 * are wrapped in an authenticated AES-256-GCM envelope. The content hash is authenticated
 * as associated data, preventing a valid ciphertext from being moved to another chunk key.
 * Writing a chunk whose hash already exists is a no-op, which is what makes successive
 * snapshots store only their changed blocks.
 */
@Component
public class ChunkStore {
    private static final byte[] ENCRYPTED_MAGIC = "FTDMCS01".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GZIP_MAGIC = {(byte) 0x1f, (byte) 0x8b};
    private static final byte[] KDF_CONTEXT = "ForgeTDM/TimeFlow/ChunkStore/v1\0"
            .getBytes(StandardCharsets.UTF_8);
    private static final int KEY_FINGERPRINT_BYTES = 8;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Path poolRoot;
    private final Encryption encryption;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ChunkStore(ForgeProps props, MaskingSecretResolver secretResolver) {
        this(defaultRoot(), stagingEncryptionEnabled(props),
                stagingEncryptionEnabled(props) ? secretResolver.resolve().secret() : null);
    }

    public ChunkStore() {
        this(defaultRoot());
    }

    public ChunkStore(Path poolRoot) {
        this(poolRoot, false, null);
    }

    /** Test/deployment constructor for an explicitly configured pool. */
    public ChunkStore(Path poolRoot, boolean encrypt, String secret) {
        this.poolRoot = poolRoot;
        this.encryption = encrypt ? Encryption.from(secret) : null;
    }

    public Path root() { return poolRoot; }
    public boolean encryptedAtRest() { return encryption != null; }

    /** Result of storing one chunk: its content hash and whether it was new to the pool. */
    public record PutResult(String hash, boolean stored, long logicalBytes, long storedBytes) {}

    /** Store a chunk; deduplicated by content hash. */
    public PutResult put(byte[] content) {
        if (content == null) throw ApiException.bad("Storage pool content is required");
        String hash = sha256(content);
        Path file = chunkPath(hash);
        try {
            if (Files.exists(file)) {
                if (encryption != null && !isEncrypted(file)) upgradeLegacyChunk(file, hash);
                return new PutResult(hash, false, content.length, Files.size(file));
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + Thread.currentThread().getId());
            Files.write(tmp, encode(content, hash));
            boolean stored;
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
                stored = true;
            } catch (AtomicMoveNotSupportedException unsupported) {
                stored = moveOrAcceptConcurrentWriter(tmp, file);
            } catch (FileAlreadyExistsException concurrent) {
                Files.deleteIfExists(tmp);
                stored = false;
            }
            return new PutResult(hash, stored, content.length, Files.size(file));
        } catch (IOException e) {
            throw ApiException.bad("Storage pool write failed: " + e.getMessage());
        }
    }

    /** Fetch, authenticate/decrypt when needed, and decompress a chunk by hash. */
    public byte[] get(String hash) {
        Path file = chunkPath(hash);
        if (!Files.exists(file)) throw ApiException.bad("Chunk " + hash + " missing from storage pool");
        try {
            return decode(Files.readAllBytes(file), hash);
        } catch (IOException e) {
            throw ApiException.bad("Storage pool read failed for chunk " + hash + ": " + e.getMessage());
        }
    }

    public boolean exists(String hash) {
        return Files.exists(chunkPath(hash));
    }

    public record PoolStats(long chunkCount, long storedBytes) {}

    /** Physical pool usage (deduplicated, compressed on-disk footprint). */
    public PoolStats stats() {
        long[] acc = new long[2];
        Path chunks = poolRoot.resolve("chunks");
        if (!Files.exists(chunks)) return new PoolStats(0, 0);
        try (Stream<Path> walk = Files.walk(chunks)) {
            walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".gz"))
                .forEach(p -> {
                    acc[0]++;
                    try { acc[1] += Files.size(p); } catch (IOException ignored) { }
                });
        } catch (IOException e) {
            throw ApiException.bad("Storage pool stats failed: " + e.getMessage());
        }
        return new PoolStats(acc[0], acc[1]);
    }

    private Path chunkPath(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw ApiException.bad("Illegal chunk hash");
        return poolRoot.resolve("chunks").resolve(hash.substring(0, 2)).resolve(hash + ".gz");
    }

    private byte[] encode(byte[] content, String hash) {
        byte[] compressed = gzip(content);
        if (encryption == null) return compressed;
        try {
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryption.key(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(hash.getBytes(StandardCharsets.US_ASCII));
            byte[] ciphertext = cipher.doFinal(compressed);
            return ByteBuffer.allocate(ENCRYPTED_MAGIC.length + KEY_FINGERPRINT_BYTES
                            + GCM_NONCE_BYTES + ciphertext.length)
                    .put(ENCRYPTED_MAGIC)
                    .put(encryption.fingerprint())
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (Exception e) {
            throw ApiException.bad("Storage pool encryption failed: " + rootMessage(e));
        } finally {
            Arrays.fill(compressed, (byte) 0);
        }
    }

    private byte[] decode(byte[] stored, String hash) {
        if (startsWith(stored, ENCRYPTED_MAGIC)) {
            if (encryption == null) {
                throw ApiException.bad("Chunk " + hash
                        + " is encrypted; enable forgetdm.staging.encrypt with the original key");
            }
            int header = ENCRYPTED_MAGIC.length + KEY_FINGERPRINT_BYTES + GCM_NONCE_BYTES;
            if (stored.length <= header) throw ApiException.bad("Encrypted chunk " + hash + " is truncated");
            ByteBuffer input = ByteBuffer.wrap(stored);
            input.position(ENCRYPTED_MAGIC.length);
            byte[] fingerprint = new byte[KEY_FINGERPRINT_BYTES];
            input.get(fingerprint);
            if (!MessageDigest.isEqual(fingerprint, encryption.fingerprint())) {
                throw ApiException.bad("Chunk " + hash + " was encrypted with a different staging key");
            }
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            input.get(nonce);
            byte[] ciphertext = new byte[input.remaining()];
            input.get(ciphertext);
            byte[] compressed = null;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, encryption.key(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
                cipher.updateAAD(hash.getBytes(StandardCharsets.US_ASCII));
                compressed = cipher.doFinal(ciphertext);
                return gunzip(compressed);
            } catch (Exception e) {
                throw ApiException.bad("Encrypted chunk " + hash
                        + " failed authentication; it may be corrupted or use the wrong key");
            } finally {
                if (compressed != null) Arrays.fill(compressed, (byte) 0);
                Arrays.fill(ciphertext, (byte) 0);
            }
        }
        if (!startsWith(stored, GZIP_MAGIC)) {
            throw ApiException.bad("Chunk " + hash + " has an unsupported storage format");
        }
        return gunzip(stored);
    }

    private void upgradeLegacyChunk(Path file, String hash) throws IOException {
        byte[] legacy = Files.readAllBytes(file);
        if (startsWith(legacy, ENCRYPTED_MAGIC)) return;
        byte[] content = decode(legacy, hash);
        Path tmp = file.resolveSibling(file.getFileName() + ".encrypt-" + Thread.currentThread().getId());
        try {
            Files.write(tmp, encode(content, hash));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Arrays.fill(content, (byte) 0);
            Files.deleteIfExists(tmp);
        }
    }

    private static boolean moveOrAcceptConcurrentWriter(Path tmp, Path file) throws IOException {
        try {
            Files.move(tmp, file);
            return true;
        } catch (FileAlreadyExistsException concurrent) {
            Files.deleteIfExists(tmp);
            return false;
        }
    }

    private static byte[] gzip(byte[] content) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(content);
            out.close();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw ApiException.bad("Storage pool compression failed: " + e.getMessage());
        }
    }

    private static byte[] gunzip(byte[] compressed) {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw ApiException.bad("Storage pool decompression failed: " + e.getMessage());
        }
    }

    private static boolean isEncrypted(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(ENCRYPTED_MAGIC.length), ENCRYPTED_MAGIC);
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static boolean stagingEncryptionEnabled(ForgeProps props) {
        return props.getStaging() != null && props.getStaging().isEncrypt();
    }

    private static Path defaultRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("forgetdm-virtualization").resolve("pool");
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record Encryption(SecretKeySpec key, byte[] fingerprint) {
        private static Encryption from(String secret) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("Encrypted staging requires a non-empty masking secret");
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(KDF_CONTEXT);
                byte[] keyBytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
                byte[] fingerprint = Arrays.copyOf(
                        MessageDigest.getInstance("SHA-256").digest(keyBytes), KEY_FINGERPRINT_BYTES);
                SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
                Arrays.fill(keyBytes, (byte) 0);
                return new Encryption(key, fingerprint);
            } catch (Exception e) {
                throw new IllegalStateException("Could not derive encrypted staging key", e);
            }
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw ApiException.bad("SHA-256 unavailable: " + e.getMessage());
        }
    }
}
