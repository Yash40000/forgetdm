package io.forgetdm.provision;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SyntheticGeneratorSeedParityTest {

    @Test
    void tableSeedIsStableAcrossGenerationReceivers() {
        Random bounded = SyntheticGenService.tableRandom(73421L, 0);
        Random streaming = SyntheticGenService.tableRandom(73421L, 0);
        Random file = SyntheticGenService.tableRandom(73421L, 0);

        for (int i = 0; i < 20; i++) {
            long expected = bounded.nextLong();
            assertEquals(expected, streaming.nextLong());
            assertEquals(expected, file.nextLong());
        }
    }

    @Test
    void differentTableIndexesUseDifferentStreams() {
        assertNotEquals(SyntheticGenService.tableRandom(73421L, 0).nextLong(),
                SyntheticGenService.tableRandom(73421L, 1).nextLong());
    }
}
