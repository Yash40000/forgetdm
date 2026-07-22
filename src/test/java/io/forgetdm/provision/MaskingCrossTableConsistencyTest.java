package io.forgetdm.provision;

import io.forgetdm.audit.AuditService;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.ri.RiRegistryService;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.GovernedReferenceGuard;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.subset.SubsetService;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaskingCrossTableConsistencyTest {

    private static final String SECRET = "mask004-local-test-secret";
    private static final String VALUE = "CUST-10025";

    @Test
    void canonicalSemanticFunctionsIgnorePhysicalTableAndColumnNames() {
        MaskingRuleEntity rule = rule("NATIONAL_ID");
        String firstSalt = ProvisioningService.saltFor(rule, "core_customer", "customer_no", Map.of());
        String secondSalt = ProvisioningService.saltFor(rule, "crm_party", "party_reference", Map.of());
        assertEquals(firstSalt, secondSalt);

        MaskingEngine engine = new MaskingEngine(SECRET).withSeed("mask004-seed");
        assertEquals(
                engine.mask(MaskFunction.NATIONAL_ID, firstSalt, VALUE, "US", "PRESERVE_FORMAT", new MaskContext(1)),
                engine.mask(MaskFunction.NATIONAL_ID, secondSalt, VALUE, "US", "PRESERVE_FORMAT", new MaskContext(99)));
    }

    @Test
    void nonSemanticParentAndChildKeysShareOneSalt() throws Exception {
        List<ProvisioningService.KeyEdge> edges = List.of(
                edge("customers", "customer_id", "accounts", "customer_id"));
        Map<String, String> salts = sharedSalts(edges);

        String parent = salts.get("customers.customer_id");
        String child = salts.get("accounts.customer_id");
        assertNotNull(parent);
        assertEquals(parent, child);

        MaskingRuleEntity rule = rule("FORMAT_PRESERVE");
        MaskingEngine engine = new MaskingEngine(SECRET).withSeed("mask004-seed");
        assertEquals(
                engine.mask(MaskFunction.FORMAT_PRESERVE,
                        ProvisioningService.saltFor(rule, "customers", "customer_id", salts), VALUE, null, null, null),
                engine.mask(MaskFunction.FORMAT_PRESERVE,
                        ProvisioningService.saltFor(rule, "accounts", "customer_id", salts), VALUE, null, null, null));
    }

    @Test
    void branchingComponentSaltIsStableWhenRelationshipOrderChanges() throws Exception {
        List<ProvisioningService.KeyEdge> forward = List.of(
                edge("customers", "customer_id", "accounts", "customer_id"),
                edge("customers", "customer_id", "loans", "borrower_id"),
                edge("accounts", "account_id", "transactions", "account_id"));
        List<ProvisioningService.KeyEdge> reversed = new ArrayList<>(forward);
        Collections.reverse(reversed);

        assertEquals(sharedSalts(forward), sharedSalts(reversed),
                "shared relationship salts must not depend on metadata/table iteration order");
    }

    @Test
    void compositePairsRemainSeparateComponents() throws Exception {
        Map<String, String> salts = sharedSalts(List.of(
                edge("orders", "tenant_id", "order_lines", "tenant_id"),
                edge("orders", "order_id", "order_lines", "order_id")));

        assertEquals(salts.get("orders.tenant_id"), salts.get("order_lines.tenant_id"));
        assertEquals(salts.get("orders.order_id"), salts.get("order_lines.order_id"));
        assertNotEquals(salts.get("orders.tenant_id"), salts.get("orders.order_id"),
                "aligned composite columns must not collapse into one masking domain");
    }

    @Test
    void unrelatedNonSemanticColumnsRemainColumnScoped() {
        MaskingRuleEntity rule = rule("FORMAT_PRESERVE");
        String customer = ProvisioningService.saltFor(rule, "customers", "status_code", Map.of());
        String account = ProvisioningService.saltFor(rule, "accounts", "status_code", Map.of());
        assertNotEquals(customer, account);
    }

    @Test
    void toolRelationshipUsesTheSameKeyEdgeShapeAsCatalogRelationship() throws Exception {
        SubsetService.UserRelEdge toolEdge = new SubsetService.UserRelEdge(
                "customers", "customer_id", "accounts", "customer_id", "tool");
        ProvisioningService.KeyEdge catalogShape = edge(toolEdge.parentTable(), toolEdge.parentColumn(),
                toolEdge.childTable(), toolEdge.childColumn());
        Map<String, String> salts = sharedSalts(List.of(catalogShape));
        assertEquals(salts.get("customers.customer_id"), salts.get("accounts.customer_id"));
    }

    @Test
    void catalogAndToolRelationshipsBuildTheSameConsistencyGraph() throws Exception {
        ProvisioningService service = service();
        Connection catalogConnection = mock(Connection.class);
        DatabaseMetaData catalogMetadata = mock(DatabaseMetaData.class);
        ResultSet noKeys = mock(ResultSet.class);
        ResultSet importedKey = mock(ResultSet.class);
        when(catalogConnection.getMetaData()).thenReturn(catalogMetadata);
        when(noKeys.next()).thenReturn(false);
        when(importedKey.next()).thenReturn(true, false);
        when(importedKey.getString("PKTABLE_NAME")).thenReturn("customers");
        when(importedKey.getString("PKCOLUMN_NAME")).thenReturn("customer_id");
        when(importedKey.getString("FKTABLE_NAME")).thenReturn("accounts");
        when(importedKey.getString("FKCOLUMN_NAME")).thenReturn("customer_id");
        when(catalogMetadata.getImportedKeys(null, "public", "customers")).thenReturn(noKeys);
        when(catalogMetadata.getImportedKeys(null, "public", "accounts")).thenReturn(importedKey);

        List<ProvisioningService.KeyEdge> catalogEdges = service.collectKeyEdges(
                catalogConnection, "public", List.of("customers", "accounts"), List.of());

        Connection toolConnection = mock(Connection.class);
        DatabaseMetaData toolMetadata = mock(DatabaseMetaData.class);
        ResultSet noToolKeys = mock(ResultSet.class);
        when(toolConnection.getMetaData()).thenReturn(toolMetadata);
        when(noToolKeys.next()).thenReturn(false);
        when(toolMetadata.getImportedKeys(null, "public", "customers")).thenReturn(noToolKeys);
        when(toolMetadata.getImportedKeys(null, "public", "accounts")).thenReturn(noToolKeys);
        List<ProvisioningService.KeyEdge> toolEdges = service.collectKeyEdges(
                toolConnection, "public", List.of("customers", "accounts"), List.of(
                        new SubsetService.UserRelEdge(
                                "customers", "customer_id", "accounts", "customer_id", "tool")));

        assertEquals(sharedSalts(catalogEdges), sharedSalts(toolEdges));
    }

    @Test
    void inconsistentRelationshipMaskingProducesActionableWarnings() {
        ProvisioningService service = service();
        List<ProvisioningService.KeyEdge> edges = List.of(
                edge("customers", "customer_id", "accounts", "customer_id"));
        MaskingRuleEntity parent = rule("customers", "customer_id", "FORMAT_PRESERVE");
        MaskingRuleEntity childDifferent = rule("accounts", "customer_id", "TOKENIZE");

        List<String> oneSided = service.keyMaskWarnings(edges,
                Map.of("customers", List.of(parent)), Map.of(), Map.of(), Map.of(), "public");
        assertEquals(1, oneSided.size());
        assertTrue(oneSided.get(0).contains("Only one side"));

        List<String> mismatched = service.keyMaskWarnings(edges,
                Map.of("customers", List.of(parent), "accounts", List.of(childDifferent)),
                Map.of(), Map.of(), Map.of(), "public");
        assertEquals(1, mismatched.size());
        assertTrue(mismatched.get(0).contains("different masking"));

        MaskingRuleEntity childMatching = rule("accounts", "customer_id", "FORMAT_PRESERVE");
        List<String> matched = service.keyMaskWarnings(edges,
                Map.of("customers", List.of(parent), "accounts", List.of(childMatching)),
                Map.of(), Map.of(), Map.of(), "public");
        assertTrue(matched.isEmpty());
    }

    private static MaskingRuleEntity rule(String function) {
        return rule("any_table", "any_column", function);
    }

    private static MaskingRuleEntity rule(String table, String column, String function) {
        MaskingRuleEntity rule = new MaskingRuleEntity();
        rule.setFunction(function);
        rule.setTableName(table);
        rule.setColumnName(column);
        return rule;
    }

    private static ProvisioningService service() {
        AuditService audit = mock(AuditService.class);
        return new ProvisioningService(
                mock(ProvisionJobRepository.class),
                mock(MaskingRuleRepository.class),
                mock(DataSourceService.class),
                mock(ConnectionFactory.class),
                new MaskingEngine(SECRET),
                mock(SubsetService.class),
                mock(DataSetService.class),
                audit,
                new ForgeProps(),
                mock(ExecutorService.class),
                mock(RiRegistryService.class),
                mock(DbMaskPushdown.class),
                mock(AccessControlService.class),
                new OwnershipGuard(audit),
                mock(GovernedReferenceGuard.class));
    }

    private static ProvisioningService.KeyEdge edge(String parentTable, String parentColumn,
                                                    String childTable, String childColumn) {
        return new ProvisioningService.KeyEdge(parentTable, parentColumn, childTable, childColumn);
    }

    private static Map<String, String> sharedSalts(List<ProvisioningService.KeyEdge> edges) {
        return ProvisioningService.buildKeyConsistencySalts(edges);
    }
}
