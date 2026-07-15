package io.forgetdm.provision;

import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticDataSafetyTest {
    @Test void sensitiveBankingColumnsDoNotAllowSourceDistributions() {
        var account = SyntheticDataSafety.classify("customer_account_number", Types.VARCHAR, "varchar");
        assertEquals("FINANCIAL_ACCOUNT", account.category());
        assertEquals("ACCOUNT_NUMBER", account.generator());
        assertFalse(account.sourceDistributionAllowed());

        var memo = SyntheticDataSafety.classify("transaction_memo", Types.VARCHAR, "varchar");
        assertEquals("TRANSACTION_DESCRIPTOR", memo.category());
        assertEquals("LOREM_SENTENCE", memo.generator());
        assertFalse(memo.sourceDistributionAllowed());
    }

    @Test void safeOperationalCategoriesCanUseWeightedDistributions() {
        var status = SyntheticDataSafety.classify("account_status", Types.VARCHAR, "varchar");
        assertEquals("SAFE_CATEGORICAL", status.category());
        assertEquals("STATUS", status.generator());
        assertTrue(status.sourceDistributionAllowed());
    }

    @Test void genderAwareNameHintsStaySafe() {
        var female = SyntheticDataSafety.classify("female_first_name", Types.VARCHAR, "varchar");
        assertEquals("FEMALE_FIRST_NAME", female.generator());
        assertFalse(female.sourceDistributionAllowed());

        var male = SyntheticDataSafety.classify("father_name", Types.VARCHAR, "varchar");
        assertEquals("MALE_FIRST_NAME", male.generator());
        assertFalse(male.sourceDistributionAllowed());
    }

    @Test void creditScoreIsNotClassifiedAsPaymentCard() {
        var score = SyntheticDataSafety.classify("credit_score", Types.INTEGER, "int4");
        assertEquals("BANKING_CONTROL", score.category());
        assertNotEquals("CREDIT_CARD_VISA", score.generator());
    }

    @Test void technicalIdentifiersOutrankCardAndAccountSemantics() {
        var numeric = SyntheticDataSafety.classify("CARD_CUSTOMER_ID", Types.NUMERIC, "NUMBER");
        assertEquals("TECHNICAL_IDENTIFIER", numeric.category());
        assertEquals("SEQUENCE", numeric.generator());
        assertFalse(numeric.sourceDistributionAllowed());
        assertFalse(numeric.sensitive());

        var text = SyntheticDataSafety.classify("ACCOUNT_OWNER_KEY", Types.VARCHAR, "VARCHAR2");
        assertEquals("PADDED_SEQUENCE", text.generator());
        assertEquals("PADDED_SEQUENCE", SyntheticDataSafety.suggestedGenerator("CARD_CUSTOMER_ID"));

        var actualCard = SyntheticDataSafety.classify("CARD_NUMBER", Types.VARCHAR, "VARCHAR2");
        assertEquals("PCI_CARD", actualCard.category());
        assertEquals("CREDIT_CARD_VISA", actualCard.generator());
    }

    @Test void governmentIdentifiersRemainSensitive() {
        var taxId = SyntheticDataSafety.classify("CUSTOMER_TAX_ID", Types.VARCHAR, "VARCHAR2");
        assertEquals("PII_TAX_ID", taxId.category());
        assertTrue(taxId.sensitive());
        assertNotEquals("PADDED_SEQUENCE", taxId.generator());
    }

    @Test void paymentCardGeneratorMustFitNumericTargetIntegerDigits() {
        var column = new SyntheticGenService.GenColumn("CARD_CUSTOMER_ID", "CREDIT_CARD_VISA", "", "",
                false, null, null, "DECIMAL", null, null);
        var table = new SyntheticGenService.GenTable("CARD_CUSTOMERS", 100L, List.of(column));

        var issues = SyntheticRangeChecks.check(table, 100,
                Map.of("card_customer_id", Types.NUMERIC),
                Map.of("card_customer_id", 18),
                Map.of("card_customer_id", 4));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).fatal());
        assertTrue(issues.get(0).message().contains("only 14 integer digits"));
        assertTrue(issues.get(0).message().contains("choose SEQUENCE"));
    }

    @Test void savedSyntheticJobNamesRequireEightCharacters() {
        ApiException error = assertThrows(ApiException.class, () -> SyntheticGenService.cleanSavedJobName("SHORT"));
        assertTrue(error.getMessage().contains("at least 8 characters"));
        assertEquals("VALID JOB", SyntheticGenService.cleanSavedJobName("  VALID JOB  "));
    }
}
