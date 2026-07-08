package io.forgetdm.provision;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticBankingReadinessTest {

    @Test
    void fullBankingPlanScoresBankingReady() {
        var plan = new SyntheticGenService.GenPlan("banking", List.of(
                table("branches", col("branch_id", "SEQUENCE", true, null), col("routing_number", "ROUTING_NUMBER_US", false, null)),
                table("customers", col("customer_id", "SEQUENCE", true, null), col("risk_segment", "WEIGHTED", false, null), col("kyc_status", "WEIGHTED", false, null)),
                table("accounts", col("account_id", "SEQUENCE", true, null), col("customer_id", "SEQUENCE", false, "customers.customer_id"), col("status", "WEIGHTED", false, null), col("routing_number", "LOOKUP", false, null)),
                table("cards", col("card_id", "SEQUENCE", true, null), col("account_id", "SEQUENCE", false, "accounts.account_id"), col("card_status", "WEIGHTED", false, null)),
                table("loans", col("loan_id", "SEQUENCE", true, null), col("customer_id", "SEQUENCE", false, "customers.customer_id"), col("delinquency_bucket", "WEIGHTED", false, null), col("loan_status", "CASE", false, null)),
                table("merchants", col("merchant_id", "SEQUENCE", true, null)),
                table("payments", col("payment_id", "SEQUENCE", true, null), col("account_id", "SEQUENCE", false, "accounts.account_id"), col("fraud_flag", "BOOLEAN_WEIGHTED", false, null)),
                table("statements", col("statement_id", "SEQUENCE", true, null), col("account_id", "SEQUENCE", false, "accounts.account_id"), col("statement_end", "DATE_AFTER", false, null))
        ), 42L, "DB", 1L, "public", false, false, null, "INSERT", "NONE", List.of(), 5000, 0, false, 1000, true);

        Map<String, Object> readiness = SyntheticBankingReadiness.evaluate(plan);

        assertTrue((Integer) readiness.get("score") >= 88, readiness.toString());
        assertEquals("BANKING_READY", readiness.get("rating"));
    }

    @Test
    void thinPlanShowsGaps() {
        var plan = new SyntheticGenService.GenPlan("tiny", List.of(
                table("customers", col("customer_id", "SEQUENCE", true, null))
        ), 42L, "CSV", null, null, false, false, null, "INSERT", "NONE", List.of(), 5000, 0, false, 1000, false);

        Map<String, Object> readiness = SyntheticBankingReadiness.evaluate(plan);

        assertTrue((Integer) readiness.get("score") < 40, readiness.toString());
        assertEquals("NEEDS_BANKING_RULES", readiness.get("rating"));
    }

    private static SyntheticGenService.GenTable table(String name, SyntheticGenService.GenColumn... columns) {
        return new SyntheticGenService.GenTable(name, 10L, List.of(columns));
    }

    private static SyntheticGenService.GenColumn col(String name, String generator, boolean pk, String fk) {
        String fkTable = null, fkColumn = null;
        if (fk != null) {
            int dot = fk.indexOf('.');
            fkTable = dot < 0 ? null : fk.substring(0, dot);
            fkColumn = dot < 0 ? null : fk.substring(dot + 1);
        }
        return new SyntheticGenService.GenColumn(name, generator, "", "", pk, fkTable, fkColumn, "", null, null);
    }
}
