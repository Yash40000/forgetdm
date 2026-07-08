package io.forgetdm.core;

import io.forgetdm.subset.SubsetService;
import io.forgetdm.subset.SubsetService.FkEdge;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubsetLogicTest {
    @Test void topoOrderLoadsParentsFirst() {
        List<FkEdge> edges = List.of(
            new FkEdge("accounts", "customer_id", "customers", "id"),
            new FkEdge("transactions", "account_id", "accounts", "id"),
            new FkEdge("addresses", "customer_id", "customers", "id"));
        List<String> order = SubsetService.topoOrder(
            new LinkedHashSet<>(List.of("transactions", "accounts", "addresses", "customers")), edges);
        assertTrue(order.indexOf("customers") < order.indexOf("accounts"));
        assertTrue(order.indexOf("accounts") < order.indexOf("transactions"));
    }

    @Test void filterGuardBlocksInjection() {
        assertThrows(RuntimeException.class, () -> SubsetService.guardFilter("1=1; DROP TABLE x"));
        assertThrows(RuntimeException.class, () -> SubsetService.guardFilter("x = 1 -- y"));
        assertDoesNotThrow(() -> SubsetService.guardFilter("state = 'TX' AND vip = true"));
    }

    @Test void rowLimitIsOptionalAndCapped() {
        assertNull(SubsetService.normalizeRowLimit(0));
        assertNull(SubsetService.normalizeRowLimit(-5));
        assertEquals(1000, SubsetService.normalizeRowLimit(1000));
        assertEquals(SubsetService.MAX_DRIVER_ROWS, SubsetService.normalizeRowLimit(SubsetService.MAX_DRIVER_ROWS + 1));
    }

    @Test void traversalModeReflectsQ1Q2Flags() {
        assertEquals("DRIVER_ROW_LIMIT", SubsetService.traversalMode(false, true, true));
        assertEquals("DRIVER_ROW_LIMIT", SubsetService.traversalMode(true, false, false));
        assertEquals("Q1_PARENT_CLOSURE", SubsetService.traversalMode(true, true, false));
        assertEquals("Q2_CHILD_CLOSURE", SubsetService.traversalMode(true, false, true));
        assertEquals("Q1_Q2_CYCLE_CLOSURE", SubsetService.traversalMode(true, true, true));
    }
}
