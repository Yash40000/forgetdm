package io.forgetdm.provision;

import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValueListAdaptTest {

    @Test
    void plainListForEnumStaysBare() {
        assertEquals("A1|A2|A3", ValueListService.adapt("A1|A2|A3", false));
    }

    @Test
    void weightedListForEnumStripsNumericWeights() {
        assertEquals("A1|A2|A3", ValueListService.adapt("A1:60|A2:30|A3:10", false));
    }

    @Test
    void plainListForWeightedGetsDefaultWeights() {
        assertEquals("P1:1|P2:1|P3:1", ValueListService.adapt("P1|P2|P3", true));
    }

    @Test
    void weightedListForWeightedIsKeptVerbatim() {
        assertEquals("A1:60|A2:30|A3:10", ValueListService.adapt("A1:60|A2:30|A3:10", true));
    }

    @Test
    void literalColonsInValuesAreNotMistakenForWeights() {
        // "10:30" ends in digits but "AM/PM" variants and non-numeric tails must survive both ways
        assertEquals("SAV:PLUS|CHK", ValueListService.adapt("SAV:PLUS|CHK", false));
        assertEquals("SAV:PLUS:1|CHK:1", ValueListService.adapt("SAV:PLUS|CHK", true));
        // a true numeric tail IS a weight
        assertEquals("10:30", ValueListService.adapt("10:30", true));
        assertEquals("10", ValueListService.adapt("10:30", false));
    }

    @Test
    void blankAndEmptyEntriesAreDropped() {
        assertEquals("A|B", ValueListService.adapt(" A | |B| ", false));
        assertThrows(ApiException.class, () -> ValueListService.adapt(" | | ", false));
    }
}
