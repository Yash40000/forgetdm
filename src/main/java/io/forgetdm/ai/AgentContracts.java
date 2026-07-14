package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Typed boundary between probabilistic intent extraction and deterministic ForgeTDM planning. */
public final class AgentContracts {
    private AgentContracts() {}

    public record Condition(String field, String operator, String value, boolean negative) {}

    public record TestDataIntent(
            String objective,
            List<String> capabilities,
            List<String> businessEntities,
            List<String> sourceHints,
            String targetHint,
            List<Condition> conditions,
            Long requestedRows,
            Long requestedEntities,
            String privacyMode,
            boolean includeNegativeCases,
            List<String> validations,
            boolean reservationRequired,
            Integer reservationHours,
            String deliveryMode,
            String outputFormat,
            List<String> assumptions,
            List<String> questions
    ) {
        public TestDataIntent {
            objective = text(objective, "Provision test data for the supplied scenario");
            capabilities = list(capabilities);
            businessEntities = list(businessEntities);
            sourceHints = list(sourceHints);
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
            privacyMode = text(privacyMode, "MASKED").toUpperCase();
            validations = list(validations);
            deliveryMode = text(deliveryMode, "DATABASE").toUpperCase();
            outputFormat = text(outputFormat, "DATABASE").toUpperCase();
            assumptions = list(assumptions);
            questions = list(questions);
        }
    }

    public record Evidence(
            long documentId,
            String citation,
            String type,
            String title,
            double score,
            String reason,
            JsonNode metadata
    ) {}

    public record ValidationIssue(String severity, String code, String message, String remediation) {}

    public record CompiledStep(
            int ordinal,
            String code,
            String title,
            String detail,
            String operation,
            boolean changesData,
            boolean requiresApproval,
            String actionName,
            JsonNode actionArgs,
            List<String> evidence,
            String status,
            String result
    ) {}

    public record Compilation(
            TestDataIntent intent,
            String summary,
            List<CompiledStep> steps,
            List<Evidence> evidence,
            List<ValidationIssue> validation,
            List<String> questions,
            double confidence,
            String riskLevel,
            String fingerprint,
            boolean modelAssisted,
            String providerId,
            String modelName
    ) {}

    public static ObjectNode intentSchema(ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");
        string(props, "objective");
        stringArray(props, "capabilities");
        stringArray(props, "businessEntities");
        stringArray(props, "sourceHints");
        nullableString(props, "targetHint");
        ObjectNode conditions = props.putObject("conditions");
        conditions.put("type", "array");
        ObjectNode condition = conditions.putObject("items");
        condition.put("type", "object");
        condition.put("additionalProperties", false);
        ObjectNode cp = condition.putObject("properties");
        string(cp, "field");
        string(cp, "operator");
        string(cp, "value");
        cp.putObject("negative").put("type", "boolean");
        required(condition, "field", "operator", "value", "negative");
        nullableLong(props, "requestedRows");
        nullableLong(props, "requestedEntities");
        enumString(props, "privacyMode", "MASKED", "SYNTHETIC", "TOKENIZED", "UNMASKED_ALLOWED", "INHERIT");
        props.putObject("includeNegativeCases").put("type", "boolean");
        stringArray(props, "validations");
        props.putObject("reservationRequired").put("type", "boolean");
        nullableInteger(props, "reservationHours");
        enumString(props, "deliveryMode", "DATABASE", "FILE", "VIRTUAL_DATABASE", "API");
        string(props, "outputFormat");
        stringArray(props, "assumptions");
        stringArray(props, "questions");
        required(root, "objective", "capabilities", "businessEntities", "sourceHints", "targetHint", "conditions",
                "requestedRows", "requestedEntities", "privacyMode", "includeNegativeCases", "validations",
                "reservationRequired", "reservationHours", "deliveryMode", "outputFormat", "assumptions", "questions");
        return root;
    }

    private static void string(ObjectNode props, String name) {
        props.putObject(name).put("type", "string");
    }

    private static void nullableString(ObjectNode props, String name) {
        ArrayNode type = props.putObject(name).putArray("type");
        type.add("string").add("null");
    }

    private static void stringArray(ObjectNode props, String name) {
        ObjectNode node = props.putObject(name);
        node.put("type", "array");
        node.putObject("items").put("type", "string");
    }

    private static void nullableLong(ObjectNode props, String name) {
        ArrayNode type = props.putObject(name).putArray("type");
        type.add("integer").add("null");
    }

    private static void nullableInteger(ObjectNode props, String name) {
        nullableLong(props, name);
    }

    private static void enumString(ObjectNode props, String name, String... values) {
        ObjectNode node = props.putObject(name);
        node.put("type", "string");
        ArrayNode valuesNode = node.putArray("enum");
        for (String value : values) valuesNode.add(value);
    }

    private static void required(ObjectNode node, String... names) {
        ArrayNode required = node.putArray("required");
        for (String name : names) required.add(name);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
    }
}
