package io.forgetdm.policy;

import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaskingPreviewSeedWiringTest {

    @Test
    void maskingStudioForwardsTheSeedWithoutCaseFoldingOrPreTrimming() {
        RecordingEngine engine = new RecordingEngine();
        PolicyController controller = new PolicyController(null, null, engine, null, null, null, null);

        controller.preview(Map.of(
                "function", "TOKENIZE",
                "value", "customer-10025",
                "param1", "TKN_",
                "param2", "32",
                "seed", "  Mixed Case Seed  "));
        controller.preview(Map.of(
                "function", "TOKENIZE",
                "value", "customer-10025",
                "param1", "TKN_",
                "param2", "32",
                "seed", "   "));

        assertEquals(List.of("  Mixed Case Seed  ", "   "), engine.seeds);
    }

    private static final class RecordingEngine extends MaskingEngine {
        private final List<String> seeds = new ArrayList<>();

        private RecordingEngine() {
            super("mask003-preview-secret");
        }

        @Override
        public MaskingEngine withSeed(String seed) {
            seeds.add(seed);
            return super.withSeed(seed);
        }
    }
}
