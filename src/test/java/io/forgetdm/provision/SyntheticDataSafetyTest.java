package io.forgetdm.provision;

import org.junit.jupiter.api.Test;

import java.sql.Types;

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
}
