package io.forgetdm.virtualization;

import io.forgetdm.common.ApiException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The "storage pool": a content-addressed, deduplicated, compressed chunk store.
 *
 * Every chunk (a batch of serialized rows, a table manifest, a snapshot manifest)
 * is keyed by the SHA-256 of its uncompressed content and stored gzip-compressed at
 * pool/chunks/&lt;aa&gt;/&lt;hash&gt;.gz. Writing a chunk whose hash already exists is a no-op,
 * which is what makes successive snapshots store only their changed blocks.
 */
@Component
public class ChunkStore {
    private final Path poolRoot;

    public ChunkStore() {
        this(Path.of(System.getProperty("java.io.tmpdir")).resolve("forgetdm-virtualization").resolve("pool"));
    }

    public ChunkStore(Path poolRoot) {
        this.poolRoot = poolRoot;
    }

    public Path root() { return poolRoot; }

    /** Result of storing one chunk: its content hash and whether it was new to the pool. */
    public record PutResult(String hash, boolean stored, long logicalBytes, long storedBytes) {}

    /** Store a chunk; deduplicated by content hash. */
    public PutResult put(byte[] content) {
        String hash = sha256(content);
        Path file = chunkPath(hash);
        try {
            if (Files.exists(file)) {
                return new PutResult(hash, false, content.length, Files.size(file));
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp-" + Thread.currentThread().getId());
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write(content);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException concurrent) {
                // another writer won the race; the content is identical by construction
                Files.deleteIfExists(tmp);
            }
            return new PutResult(hash, true, content.length, Files.size(file));
        } catch (IOException e) {
            throw ApiException.bad("Storage pool write failed: " + e.getMessage());
        }
    }

    /** Fetch and decompress a chunk by hash. */
    public byte[] get(String hash) {
        Path file = chunkPath(hash);
        if (!Files.exists(file)) throw ApiException.bad("Chunk " + hash + " missing from storage pool");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
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

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw ApiException.bad("SHA-256 unavailable: " + e.getMessage());
        }
    }
}
