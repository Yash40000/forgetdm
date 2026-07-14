package io.forgetdm.filevault;

import io.forgetdm.config.ForgeProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ManagedFileVaultTest {
    @TempDir Path temp;

    @Test void encryptsUploadsAndStreamsClearOutputOnlyThroughVault() throws Exception {
        ForgeProps props = new ForgeProps(); props.setMaskingSecret("unit-test-secret"); props.setCapsuleSecret("unit-test-capsule-secret");
        ManagedFileVault vault = new ManagedFileVault(props, temp.toString()); vault.init();
        byte[] clear = "jane.doe@example.com".getBytes(StandardCharsets.UTF_8);
        ManagedFileVault.Stored stored = vault.store(new ByteArrayInputStream(clear), clear.length);

        byte[] disk = Files.readAllBytes(temp.resolve(stored.storageKey()));
        assertFalse(new String(disk, StandardCharsets.UTF_8).contains("jane.doe"));
        assertArrayEquals(clear, vault.open(stored.storageKey(), stored.keySalt(), stored.iv()).readAllBytes());
        assertEquals(64, stored.sha256().length());
    }

    @Test void outputHandleRecordsClearDigestAndCanBeDecrypted() throws Exception {
        ForgeProps props = new ForgeProps(); props.setMaskingSecret("unit-test-secret");
        ManagedFileVault vault = new ManagedFileVault(props, temp.toString()); vault.init();
        ManagedFileVault.OutputHandle output = vault.createOutput();
        try (output) { output.stream().write("masked-value".getBytes(StandardCharsets.UTF_8)); }
        assertEquals("masked-value", new String(vault.open(output.storageKey(), output.keySalt(), output.iv()).readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(64, output.sha256().length());
    }
}
