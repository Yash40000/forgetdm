package io.forgetdm.policy;

import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyRuleValidationTest {

    @Test void secureLookupAndScriptsRequireGovernedSources() {
        assertThrows(ApiException.class, () -> PolicyController.validateRuleConfig(MaskFunction.SECURE_LOOKUP, null, null));
        assertThrows(ApiException.class, () -> PolicyController.validateRuleConfig(MaskFunction.SCRIPT, "", null));
        assertDoesNotThrow(() -> PolicyController.validateRuleConfig(MaskFunction.SECURE_LOOKUP, "F|M|X", "UPPER"));
        assertThrows(ApiException.class, () -> PolicyController.validateRuleConfig(MaskFunction.DIRECT_LOOKUP, null, null));
        assertThrows(ApiException.class, () -> PolicyController.validateRuleConfig(MaskFunction.HASH_LOOKUP, "", null));
        assertDoesNotThrow(() -> PolicyController.validateRuleConfig(MaskFunction.DIRECT_LOOKUP, "A=>Alpha|B=>Beta", "NOT_FOUND=ERROR"));
        assertDoesNotThrow(() -> PolicyController.validateRuleConfig(MaskFunction.HASH_LOOKUP, "Avery|Jordan", "SEED=7"));
    }

    @Test void numericPoliciesRejectBadBoundsBeforeExecution() {
        assertThrows(ApiException.class, () -> PolicyController.validateRuleConfig(MaskFunction.NUMERIC_NOISE, "PERCENT:ten", null));
        assertThrows(ApiException.class, () -> PolicyController.validateRuleConfig(MaskFunction.NUMERIC_NOISE, "ABS:5", "100:10"));
        assertThrows(ApiException.class, () -> PolicyController.validateRuleConfig(MaskFunction.MIN_MAX, "20", "10"));
        assertDoesNotThrow(() -> PolicyController.validateRuleConfig(MaskFunction.NUMERIC_NOISE, "PERCENT:5.5", "0:1000"));
        assertDoesNotThrow(() -> PolicyController.validateRuleConfig(MaskFunction.MIN_MAX, "10", "20"));
    }

    @Test void tokenLengthAndCompositeMapsAreValidated() {
        assertThrows(ApiException.class, () -> PolicyController.validateRuleConfig(MaskFunction.TOKENIZE, "TKN_", "8"));
        assertThrows(ApiException.class, () -> PolicyController.validateRuleConfig(MaskFunction.BY_INDICATOR, "type", null));
        assertDoesNotThrow(() -> PolicyController.validateRuleConfig(MaskFunction.TOKENIZE, "TKN_", "32"));
        assertDoesNotThrow(() -> PolicyController.validateRuleConfig(MaskFunction.BY_INDICATOR, "type", "P=PHONE|*=REDACT"));
    }
}
