package io.forgetdm.provision;

import io.forgetdm.policy.MaskingRuleEntity;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DerivedNameProvisioningOrderTest {

    @Test
    void nameComponentsAreEvaluatedBeforeDerivedColumnsWithoutChangingWriterOrder() {
        List<ProvisioningService.ColumnPlan> plans = List.of(
                plan("full_name"), plan("email"), plan("last_name"), plan("first_name"), plan("status"));
        Map<String, MaskingRuleEntity> rules = new LinkedHashMap<>();
        rules.put("full_name", rule("full_name", "FULL_NAME"));
        rules.put("email", rule("email", "EMAIL"));
        rules.put("last_name", rule("last_name", "LAST_NAME"));
        rules.put("first_name", rule("first_name", "FIRST_NAME"));

        assertEquals(List.of(2, 3, 4, 0, 1), ProvisioningService.maskingEvaluationOrder(plans, rules));
        assertEquals(List.of("full_name", "email", "last_name", "first_name", "status"),
                plans.stream().map(ProvisioningService.ColumnPlan::targetColumn).toList());
    }

    private static ProvisioningService.ColumnPlan plan(String column) {
        return new ProvisioningService.ColumnPlan(column, column, null, Types.VARCHAR, 100, 0);
    }

    private static MaskingRuleEntity rule(String column, String function) {
        MaskingRuleEntity rule = new MaskingRuleEntity();
        rule.setColumnName(column);
        rule.setFunction(function);
        return rule;
    }
}
