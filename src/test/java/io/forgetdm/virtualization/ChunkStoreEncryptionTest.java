package io.forgetdm.virtualization;

import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStoreEncryptionTest {

    private static final String KEY = "unit-test-staging-key-with-enough-entropy";

    @TempDir Path temp;

    @Test
    void encryptedChunksRoundTripWithoutPlaintextAtRest() throws Exception {
        byte[] content = "customer=554321;address=12 Main Street".getBytes(StandardCharsets.UTF_8);
        ChunkStore store = new ChunkStore(temp, true, KEY);

        ChunkStore.PutResult first = store.put(content);
        ChunkStore.PutResult duplicate = store.put(content);
        byte[] disk = Files.readAllBytes(chunkPath(first.hash()));

        assertTrue(first.stored());
        assertFalse(duplicate.stored());
        assertTrue(store.encryptedAtRest());
        assertArrayEquals("FTDMCS01".getBytes(StandardCharsets.US_ASCII),
                java.util.Arrays.copyOf(disk, 8));
        assertFalse(new String(disk, StandardCharsets.ISO_8859_1).contains("554321"));
        assertArrayEquals(content, store.get(first.hash()));
    }

    @Test
    void encryptedStoreReadsAndUpgradesLegacyGzipChunk() throws Exception {
        byte[] content = "legacy snapshot payload".getBytes(StandardCharsets.UTF_8);
        ChunkStore legacy = new ChunkStore(temp);
        String hash = legacy.put(content).hash();
        assertEquals(0x1f, Files.readAllBytes(chunkPath(hash))[0] & 0xff);

        ChunkStore encrypted = new ChunkStore(temp, true, KEY);
        assertArrayEquals(content, encrypted.get(hash), "legacy data must remain readable");
        assertFalse(encrypted.put(content).stored(), "upgrading must not count as a new logical chunk");

        byte[] upgraded = Files.readAllBytes(chunkPath(hash));
        assertArrayEquals("FTDMCS01".getBytes(StandardCharsets.US_ASCII),
                java.util.Arrays.copyOf(upgraded, 8));
        assertArrayEquals(content, encrypted.get(hash));
    }

    @Test
    void authenticationRejectsTamperingAndWrongKey() throws Exception {
        byte[] content = "protected staging data".getBytes(StandardCharsets.UTF_8);
        ChunkStore writer = new ChunkStore(temp, true, KEY);
        String hash = writer.put(content).hash();

        ApiException wrongKey = assertThrows(ApiException.class,
                () -> new ChunkStore(temp, true, "different-unit-test-staging-key").get(hash));
        assertTrue(wrongKey.getMessage().contains("different staging key"));

        Path file = chunkPath(hash);
        byte[] tampered = Files.readAllBytes(file);
        tampered[tampered.length - 1] ^= 1;
        Files.write(file, tampered);
        ApiException authentication = assertThrows(ApiException.class, () -> writer.get(hash));
        assertTrue(authentication.getMessage().contains("failed authentication"));
    }

    @Test
    void plaintextModeFailsClearlyWhenGivenEncryptedChunk() {
        byte[] content = "encrypted first".getBytes(StandardCharsets.UTF_8);
        String hash = new ChunkStore(temp, true, KEY).put(content).hash();

        ApiException error = assertThrows(ApiException.class, () -> new ChunkStore(temp).get(hash));
        assertTrue(error.getMessage().contains("enable forgetdm.staging.encrypt"));
    }

    private Path chunkPath(String hash) {
        return temp.resolve("chunks").resolve(hash.substring(0, 2)).resolve(hash + ".gz");
    }
}
