package io.forgetdm.provision;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SyntheticConstraintRulesTest {

    @Test
    void parsesAndAppliesInConstraint() {
        var rules = SyntheticConstraintRules.parseAll("accounts", "accounts_status_chk",
                "CHECK ((status IN ('ACTIVE','CLOSED','DORMANT')))");
        var row = new LinkedHashMap<String, String>();
        row.put("status", "BAD");

        SyntheticConstraintRules.apply(row, rules, 1);

        assertTrue(List.of("ACTIVE", "CLOSED", "DORMANT").contains(row.get("status")));
    }

    @Test
    void parsesAndClampsBetweenConstraint() {
        var rules = SyntheticConstraintRules.parseAll("payments", "payments_amount_chk",
                "CHECK ((amount BETWEEN 0 AND 999.99))");
        var row = new LinkedHashMap<String, String>();
        row.put("amount", "1200.44");

        SyntheticConstraintRules.apply(row, rules, 7);

        assertEquals(new BigDecimal("999.99"), new BigDecimal(row.get("amount")));
    }

    @Test
    void handlesStrictNumericBoundsInsideRange() {
        var rules = SyntheticConstraintRules.parseAll("scores", "scores_score_chk",
                "CHECK ((score > 0 AND score < 100))");
        var low = new LinkedHashMap<String, String>();
        low.put("score", "0");
        var high = new LinkedHashMap<String, String>();
        high.put("score", "100");

        SyntheticConstraintRules.apply(low, rules, 1);
        SyntheticConstraintRules.apply(high, rules, 2);

        assertEquals("1", low.get("score"));
        assertEquals("99", high.get("score"));
    }

    @Test
    void handlesPostgresNumericCastsInCheckExpressions() {
        var minRules = SyntheticConstraintRules.parseAll("accounts", "ck_acct_bal",
                "CHECK ((balance >= (0)::numeric))");
        var rangeRules = SyntheticConstraintRules.parseAll("transactions", "ck_txn_amt",
                "CHECK (((amount > (0)::numeric) AND (amount <= (1000000)::numeric)))");
        var row = new LinkedHashMap<String, String>();
        row.put("balance", "-9.25");
        row.put("amount", "-1");

        SyntheticConstraintRules.apply(row, minRules, 1);
        SyntheticConstraintRules.apply(row, rangeRules, 1);

        assertEquals("0", row.get("balance"));
        assertEquals("1", row.get("amount"));
    }

    @Test
    void doesNotPartiallyEnforceCrossColumnCheck() {
        var rules = SyntheticConstraintRules.parseAll("accounts", "accounts_balance_chk",
                "CHECK (balance >= 0 AND balance <= credit_limit)");

        assertTrue(rules.isEmpty(), rules.toString());
    }

    @Test
    void doesNotEnforceOrCompositeCheck() {
        var rules = SyntheticConstraintRules.parseAll("accounts", "accounts_status_nullable_chk",
                "CHECK (status IN ('ACTIVE','CLOSED') OR status IS NULL)");

        assertTrue(rules.isEmpty(), rules.toString());
    }

    @Test
    void doesNotEnforceFunctionOrRegexCheck() {
        var rules = SyntheticConstraintRules.parseAll("customers", "customers_code_chk",
                "CHECK (REGEXP_LIKE(customer_code, '^[A-Z]{3}[0-9]{4}$'))");

        assertTrue(rules.isEmpty(), rules.toString());
    }
}
