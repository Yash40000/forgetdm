package io.forgetdm.provision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SyntheticBankingReadiness {
    private SyntheticBankingReadiness() {}

    static Map<String, Object> evaluate(SyntheticGenService.GenPlan plan) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        List<SyntheticGenService.GenTable> tables = plan == null || plan.tables() == null ? List.of() : plan.tables();
        Set<String> names = tables.stream()
                .map(t -> normalize(t.name()))
                .collect(java.util.stream.Collectors.toSet());
        List<String> strengths = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        int score = 0;
        score += domain(names, strengths, gaps, "customers", 12, "customer domain");
        score += domain(names, strengths, gaps, "accounts", 14, "account domain");
        score += anyDomain(names, strengths, gaps, Set.of("payments", "transactions"), 12, "payment/transaction domain");
        score += domain(names, strengths, gaps, "cards", 8, "card domain");
        score += domain(names, strengths, gaps, "loans", 8, "loan domain");
        score += domain(names, strengths, gaps, "statements", 6, "statement domain");
        score += domain(names, strengths, gaps, "branches", 5, "branch domain");
        score += domain(names, strengths, gaps, "merchants", 5, "merchant domain");

        int relationshipCount = 0;
        int ruleCount = 0;
        int safetyCount = 0;
        for (SyntheticGenService.GenTable table : tables) {
            for (SyntheticGenService.GenColumn col : safe(table.columns())) {
                if (notBlank(col.fkTable()) && notBlank(col.fkColumn())) relationshipCount++;
                String gen = upper(col.generator());
                if (Set.of("CASE", "LOOKUP", "DATE_AFTER", "TEMPLATE").contains(gen)) ruleCount++;
                String cn = normalize(col.name());
                if (cn.contains("status") || cn.contains("risk") || cn.contains("kyc")
                        || cn.contains("delinquency") || cn.contains("fraud")) safetyCount++;
            }
        }
        score += Math.min(12, relationshipCount * 3);
        score += Math.min(10, ruleCount * 2);
        score += Math.min(8, safetyCount);

        if (relationshipCount >= 4) strengths.add("Referential relationships present across banking entities");
        else gaps.add("Add more explicit foreign keys between customers, accounts, cards, loans, payments, and statements");
        if (ruleCount >= 4) strengths.add("Within-row consistency rules present through derived generators");
        else gaps.add("Add CASE, LOOKUP, TEMPLATE, or DATE_AFTER rules for within-row consistency");
        if (safetyCount >= 5) strengths.add("Risk/status/fraud/KYC columns included for scenario coverage");
        else gaps.add("Add risk, KYC, fraud, delinquency, and status fields for banking scenarios");

        score = Math.max(0, Math.min(100, score));
        out.put("score", score);
        out.put("rating", score >= 88 ? "BANKING_READY" : score >= 72 ? "PILOT_READY" : "NEEDS_BANKING_RULES");
        out.put("relationshipCount", relationshipCount);
        out.put("ruleColumnCount", ruleCount);
        out.put("scenarioColumnCount", safetyCount);
        out.put("strengths", strengths);
        out.put("gaps", gaps);
        return out;
    }

    private static int domain(Set<String> names, List<String> strengths, List<String> gaps,
                              String table, int points, String label) {
        if (names.contains(table)) {
            strengths.add("Includes " + label);
            return points;
        }
        gaps.add("Missing " + label + " (" + table + ")");
        return 0;
    }

    private static int anyDomain(Set<String> names, List<String> strengths, List<String> gaps,
                                 Set<String> options, int points, String label) {
        for (String option : options) {
            if (names.contains(option)) {
                strengths.add("Includes " + label);
                return points;
            }
        }
        gaps.add("Missing " + label + " (" + String.join("/", options) + ")");
        return 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> safe(List<T> value) {
        return value == null ? List.of() : value;
    }
}
