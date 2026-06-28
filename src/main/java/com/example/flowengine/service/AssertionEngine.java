package com.example.flowengine.service;

import com.example.flowengine.DTO.AssertionResult;
import com.example.flowengine.DTO.FlowStepRequest.AssertionsRequest;
import com.example.flowengine.utils.PlaceholderUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssertionEngine {

    private final ObjectMapper objectMapper;

    /**
     * Evaluate all assertions against the HTTP response.
     * previousResponses: accumulated response bodies from prior steps (same as ExecutorService uses).
     * Assertion values support {placeholder} or {{placeholder}} syntax — resolved before comparison.
     * Returns list of AssertionResult — one per assertion checked.
     */
    public List<AssertionResult> evaluate(AssertionsRequest assertions,
                                          int actualStatusCode,
                                          String responseBody,
                                          List<String> previousResponses) {
        List<AssertionResult> results = new ArrayList<>();
        if (assertions == null) return results;

        List<String> context = previousResponses != null ? previousResponses : List.of();

        // 1. Status code check
        if (assertions.getStatusCode() != null) {
            int expected = assertions.getStatusCode();
            boolean passed = actualStatusCode == expected;
            boolean critical = assertions.getStatusCodeCritical() == null || assertions.getStatusCodeCritical();
            results.add(new AssertionResult(
                    "statusCode",
                    passed,
                    passed
                            ? "Expected " + expected + ", got " + actualStatusCode
                            : "Expected " + expected + " but got " + actualStatusCode,
                    critical
            ));
        }

        // Parse response body for schema + field assertions
        JsonNode root = null;
        if ((assertions.getSchema() != null || assertions.getBody() != null)
                && responseBody != null && !responseBody.isBlank()) {
            try {
                root = objectMapper.readTree(responseBody);
            } catch (Exception e) {
                results.add(new AssertionResult("responseBody", false,
                        "Could not parse response as JSON: " + e.getMessage()));
                return results;
            }
        }

        // 2. Schema validation (structural — no placeholders needed here)
        if (assertions.getSchema() != null && root != null) {
            boolean schemaCritical = assertions.getSchemaCritical() == null || assertions.getSchemaCritical();
            validateSchema("schema", assertions.getSchema(), root, results, schemaCritical);
        }

        // 3. Field-level body assertions (values support {placeholder} syntax)
        // Each field can include "critical": false to mark as non-critical
        if (assertions.getBody() != null && root != null && assertions.getBody().isObject()) {
            final JsonNode rootFinal = root;
            assertions.getBody().fields().forEachRemaining(entry -> {
                String path = entry.getKey();
                JsonNode operatorsNode = entry.getValue();
                JsonNode node = resolvePath(rootFinal, path);
                // Extract critical flag from the operators node — default true
                boolean critical = !operatorsNode.has("critical") || operatorsNode.get("critical").asBoolean(true);
                evaluateFieldAssertions("body." + path, node, operatorsNode, context, results, critical);
            });
        }

        return results;
    }

    // ── Schema validation ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void validateSchema(String prefix, Map<String, Object> schema,
                                JsonNode node, List<AssertionResult> results, boolean critical) {
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            String path = prefix + "." + key;

            // Key may be dot-notation (e.g. "userInfo.id") because SchemaExtractorService
            // produces a flat map. Traverse the node by each segment rather than calling
            // node.get("userInfo.id") which returns null — Jackson's get() treats the argument
            // as a literal key name, not a path.
            JsonNode child = resolvePathSegments(node, key);

            if (child == null || child.isNull()) {
                results.add(new AssertionResult(path, false,
                        "Schema field '" + key + "' is missing from response", critical));
                continue;
            }

            if (expected instanceof String) {
                String expectedType = (String) expected;
                boolean passed = matchesType(child, expectedType);
                results.add(new AssertionResult(path, passed,
                        passed
                                ? "Type check passed: " + expectedType
                                : "Expected type '" + expectedType + "' but got '" + resolveType(child) + "'",
                        critical
                ));
            } else if (expected instanceof Map) {
                if (!child.isObject()) {
                    results.add(new AssertionResult(path, false,
                            "Expected nested object at '" + key + "' but got '" + resolveType(child) + "'", critical));
                } else {
                    validateSchema(path, (Map<String, Object>) expected, child, results, critical);
                }
            }
        }
    }

    /**
     * Resolves a potentially dot-notation key against a JsonNode by traversing segment by segment.
     * e.g. resolvePathSegments(root, "userInfo.id") = root.get("userInfo").get("id")
     * Falls back to a single-key lookup first to handle keys that literally contain dots.
     */
    private JsonNode resolvePathSegments(JsonNode node, String key) {
        if (node == null) return null;
        // Try literal key first (handles genuine single-level keys)
        JsonNode direct = node.get(key);
        if (direct != null) return direct;
        // Fall back to dot-notation traversal
        String[] parts = key.split("\\.");
        JsonNode current = node;
        for (String part : parts) {
            if (current == null || current.isNull() || !current.isObject()) return null;
            current = current.get(part);
        }
        return current;
    }

    // ── Field assertion evaluation ────────────────────────────────────────────

    private void evaluateFieldAssertions(String path, JsonNode node,
                                         JsonNode operatorsNode,
                                         List<String> context,
                                         List<AssertionResult> results,
                                         boolean critical) {
        if (operatorsNode == null || !operatorsNode.isObject()) return;
        var it = operatorsNode.fields();
        while (it.hasNext()) {
            var op = it.next();
            String operator = op.getKey();
            if (operator.equals("critical")) continue; // skip the critical flag itself
            JsonNode expectedNode = op.getValue();
            Object expected = expectedNode.isTextual() ? expectedNode.asText()
                    : expectedNode.isNumber()  ? expectedNode.numberValue()
                      : expectedNode.isBoolean() ? expectedNode.booleanValue()
                        : expectedNode.isArray()   ? objectMapper.convertValue(expectedNode, List.class)
                          : expectedNode.asText();

            switch (operator) {
                case "exists" -> {
                    boolean shouldExist = Boolean.TRUE.equals(expected);
                    boolean actuallyExists = node != null && !node.isNull();
                    boolean passed = shouldExist == actuallyExists;
                    results.add(new AssertionResult(path, passed,
                            passed
                                    ? "exists=" + shouldExist + " check passed"
                                    : "Expected field to " + (shouldExist ? "exist" : "not exist") +
                                      " but it " + (actuallyExists ? "does exist" : "does not exist"),
                            critical));
                }
                case "type" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check type", critical));
                    } else {
                        String expectedType = String.valueOf(expected);
                        boolean passed = matchesType(node, expectedType);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "Type check passed: " + expectedType
                                        : "Expected type '" + expectedType + "' but got '" + resolveType(node) + "'",
                                critical));
                    }
                }
                case "equals" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check equals", critical));
                    } else {
                        String actual = node.asText();
                        String exp = PlaceholderUtils.resolveValue(String.valueOf(expected), context);
                        boolean passed = actual.equals(exp);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "equals '" + exp + "' passed"
                                        : "Expected '" + exp + "' but got '" + actual + "'",
                                critical));
                    }
                }
                case "notEquals" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check notEquals", critical));
                    } else {
                        String actual = node.asText();
                        String exp = PlaceholderUtils.resolveValue(String.valueOf(expected), context);
                        boolean passed = !actual.equals(exp);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "notEquals '" + exp + "' passed"
                                        : "Expected value to not equal '" + exp + "' but it did",
                                critical));
                    }
                }
                case "contains" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check contains", critical));
                    } else {
                        String actual = node.asText();
                        String exp = PlaceholderUtils.resolveValue(String.valueOf(expected), context);
                        boolean passed = actual.contains(exp);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "contains '" + exp + "' passed"
                                        : "Expected '" + actual + "' to contain '" + exp + "' but it does not",
                                critical));
                    }
                }
                case "greaterThan" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check greaterThan", critical));
                    } else {
                        try {
                            double actual = node.asDouble();
                            String expStr = PlaceholderUtils.resolveValue(String.valueOf(expected), context);
                            double exp = Double.parseDouble(expStr);
                            boolean passed = actual > exp;
                            results.add(new AssertionResult(path, passed,
                                    passed
                                            ? actual + " > " + exp + " passed"
                                            : "Expected " + actual + " to be greater than " + exp,
                                    critical));
                        } catch (NumberFormatException e) {
                            results.add(new AssertionResult(path, false, "greaterThan requires a numeric value", critical));
                        }
                    }
                }
                case "lessThan" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check lessThan", critical));
                    } else {
                        try {
                            double actual = node.asDouble();
                            String expStr = PlaceholderUtils.resolveValue(String.valueOf(expected), context);
                            double exp = Double.parseDouble(expStr);
                            boolean passed = actual < exp;
                            results.add(new AssertionResult(path, passed,
                                    passed
                                            ? actual + " < " + exp + " passed"
                                            : "Expected " + actual + " to be less than " + exp,
                                    critical));
                        } catch (NumberFormatException e) {
                            results.add(new AssertionResult(path, false, "lessThan requires a numeric value", critical));
                        }
                    }
                }
                case "in" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check in", critical));
                    } else {
                        String actual = node.asText();
                        @SuppressWarnings("unchecked")
                        List<Object> options = (List<Object>) expected;
                        boolean passed = options.stream()
                                .map(o -> PlaceholderUtils.resolveValue(String.valueOf(o), context))
                                .anyMatch(actual::equals);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "'" + actual + "' is in " + options
                                        : "Expected one of " + options + " but got '" + actual + "'",
                                critical));
                    }
                }
                default -> results.add(new AssertionResult(path, false,
                        "Unknown operator: '" + operator + "'", critical));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JsonNode resolvePath(JsonNode root, String path) {
        String[] parts = path.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null || current.isNull()) return null;
            current = current.get(part);
        }
        return current;
    }

    private boolean matchesType(JsonNode node, String expectedType) {
        return switch (expectedType.toLowerCase()) {
            case "number" -> node.isNumber();
            case "string" -> node.isTextual();
            case "boolean" -> node.isBoolean();
            case "array" -> node.isArray();
            case "object" -> node.isObject();
            case "null" -> node.isNull();
            default -> false;
        };
    }

    private String resolveType(JsonNode node) {
        if (node.isNumber()) return "number";
        if (node.isTextual()) return "string";
        if (node.isBoolean()) return "boolean";
        if (node.isArray()) return "array";
        if (node.isObject()) return "object";
        if (node.isNull()) return "null";
        return "unknown";
    }
}