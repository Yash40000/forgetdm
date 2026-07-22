package io.forgetdm.core;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CrossSystemReferentialIntegrityTest {

    @Test
    void logicalCustomerKeyMasksIdenticallyAcrossIndependentConnectors() {
        MaskingEngine engine = new MaskingEngine("cross-system-acceptance-key");
        List<String> systems = List.of("Temenos T24", "TSYS PRIME", "FIS CORTEX", "FIS AG Quantum");
        Map<String, String> results = new LinkedHashMap<>();

        for (String system : systems) {
            results.put(system, engine.mask(MaskFunction.FORMAT_PRESERVE, "customer.number",
                    "554321", null, null, new MaskContext(1)));
        }

        assertEquals(1, results.values().stream().distinct().count());
        String token = results.values().iterator().next();
        assertTrue(token.matches("\\d{6}"));
        assertNotEquals("554321", token);
        assertNotEquals(token, engine.mask(MaskFunction.FORMAT_PRESERVE, "customer.number",
                "554322", null, null, new MaskContext(2)));
        assertNotEquals(token, engine.mask(MaskFunction.FORMAT_PRESERVE, "account.number",
                "554321", null, null, new MaskContext(1)));
    }
}
