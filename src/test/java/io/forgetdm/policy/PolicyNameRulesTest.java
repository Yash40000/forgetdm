package io.forgetdm.policy;

import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyNameRulesTest {
    @Test
    void acceptsEnterprisePolicyNames() {
        assertEquals("CUSTOMER_360-UAT.V2", PolicyNameRules.normalize("  CUSTOMER_360-UAT.V2  "));
    }

    @Test
    void rejectsShortLongAndUnsafeNames() {
        assertThrows(ApiException.class, () -> PolicyNameRules.normalize("SHORT"));
        assertThrows(ApiException.class, () -> PolicyNameRules.normalize("A".repeat(121)));
        assertThrows(ApiException.class, () -> PolicyNameRules.normalize("POLICY/PROD"));
        assertThrows(ApiException.class, () -> PolicyNameRules.normalize("_POLICY_NAME"));
    }
}
