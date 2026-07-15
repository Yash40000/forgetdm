package io.forgetdm.subset;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationshipSelectionTest {

    private static final SubsetService.FkEdge DB =
            new SubsetService.FkEdge("ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID", "DB", null);
    private static final SubsetService.FkEdge TOOL =
            new SubsetService.FkEdge("ORDERS", "LEGACY_CUSTOMER", "CUSTOMERS", "CUSTOMER_NO", "USER", 41L);

    @Test
    void databaseRelationshipIsPreferredWhenBothExistAndNoChoiceWasSaved() {
        List<SubsetService.FkEdge> selected = SubsetService.selectRelationshipEdges(List.of(DB, TOOL), Map.of());

        assertEquals(List.of(DB), selected);
    }

    @Test
    void toolRelationshipIsAutomaticallyUsedWhenNoDatabaseRelationshipExists() {
        List<SubsetService.FkEdge> selected = SubsetService.selectRelationshipEdges(List.of(TOOL), Map.of());

        assertEquals(List.of(TOOL), selected);
    }

    @Test
    void explicitToolChoiceOverridesTheDatabaseRelationship() {
        List<SubsetService.FkEdge> selected = SubsetService.selectRelationshipEdges(
                List.of(DB, TOOL),
                Map.of(
                        "customers->orders:DB", "NONE",
                        "customers->orders:USER:41", "INHERIT"));

        assertEquals(List.of(TOOL), selected);
    }

    @Test
    void selectingNoneRemovesTheRelationshipFromTraversal() {
        List<SubsetService.FkEdge> selected = SubsetService.selectRelationshipEdges(
                List.of(DB, TOOL),
                Map.of(
                        "customers->orders:DB", "NONE",
                        "customers->orders:USER:41", "NONE"));

        assertTrue(selected.isEmpty());
    }

    @Test
    void compositeColumnsForTheSelectedRelationshipStayTogether() {
        SubsetService.FkEdge first =
                new SubsetService.FkEdge("LINE_ITEMS", "ORDER_ID", "ORDERS", "ID", "USER", 77L);
        SubsetService.FkEdge second =
                new SubsetService.FkEdge("LINE_ITEMS", "ORDER_REGION", "ORDERS", "REGION", "USER", 77L);

        List<SubsetService.FkEdge> selected = SubsetService.selectRelationshipEdges(
                List.of(first, second), Map.of("orders->line_items:USER:77", "Q1_ONLY"));

        assertEquals(List.of(first, second), selected);
    }
}
