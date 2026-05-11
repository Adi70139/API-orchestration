package com.example.flowengine.service;

import com.example.flowengine.DTO.AssertionResult;
import com.example.flowengine.DTO.FlowStepRequest.AssertionsRequest;
import com.fasterxml.jackson.databind.JsonNode;
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
     * Returns list of AssertionResult — one per assertion checked.
     */
    public List<AssertionResult> evaluate(AssertionsRequest assertions,
                                          int actualStatusCode,
                                          String responseBody) {
        List<AssertionResult> results = new ArrayList<>();
        if (assertions == null) return results;

        // 1. Status code check
        if (assertions.getStatusCode() != null) {
            int expected = assertions.getStatusCode();
            boolean passed = actualStatusCode == expected;
            results.add(new AssertionResult(
                    "statusCode",
                    passed,
                    passed
                            ? "Expected " + expected + ", got " + actualStatusCode
                            : "Expected " + expected + " but got " + actualStatusCode
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

        // 2. Schema validation
        if (assertions.getSchema() != null && root != null) {
            validateSchema("schema", assertions.getSchema(), root, results);
        }

        // 3. Field-level body assertions
        if (assertions.getBody() != null && root != null) {
            for (Map.Entry<String, Map<String, Object>> entry : assertions.getBody().entrySet()) {
                String path = entry.getKey();
                Map<String, Object> operators = entry.getValue();
                JsonNode node = resolvePath(root, path);
                evaluateFieldAssertions("body." + path, node, operators, results);
            }
        }

        return results;
    }

    // ── Schema validation ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void validateSchema(String prefix, Map<String, Object> schema,
                                JsonNode node, List<AssertionResult> results) {
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            String path = prefix + "." + key;

            JsonNode child = node.get(key);

            if (child == null || child.isNull()) {
                results.add(new AssertionResult(path, false,
                        "Schema field '" + key + "' is missing from response"));
                continue;
            }

            if (expected instanceof String) {
                // Leaf — type check
                String expectedType = (String) expected;
                boolean passed = matchesType(child, expectedType);
                results.add(new AssertionResult(path, passed,
                        passed
                                ? "Type check passed: " + expectedType
                                : "Expected type '" + expectedType + "' but got '" + resolveType(child) + "'"
                ));
            } else if (expected instanceof Map) {
                // Nested object — recurse
                if (!child.isObject()) {
                    results.add(new AssertionResult(path, false,
                            "Expected nested object at '" + key + "' but got '" + resolveType(child) + "'"));
                } else {
                    validateSchema(path, (Map<String, Object>) expected, child, results);
                }
            }
        }
    }

    // ── Field assertion evaluation ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void evaluateFieldAssertions(String path, JsonNode node,
                                         Map<String, Object> operators,
                                         List<AssertionResult> results) {
        for (Map.Entry<String, Object> op : operators.entrySet()) {
            String operator = op.getKey();
            Object expected = op.getValue();

            switch (operator) {
                case "exists" -> {
                    boolean shouldExist = Boolean.TRUE.equals(expected);
                    boolean actuallyExists = node != null && !node.isNull();
                    boolean passed = shouldExist == actuallyExists;
                    results.add(new AssertionResult(path, passed,
                            passed
                                    ? "exists=" + shouldExist + " check passed"
                                    : "Expected field to " + (shouldExist ? "exist" : "not exist") +
                                    " but it " + (actuallyExists ? "does exist" : "does not exist")
                    ));
                }
                case "type" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check type"));
                    } else {
                        String expectedType = String.valueOf(expected);
                        boolean passed = matchesType(node, expectedType);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "Type check passed: " + expectedType
                                        : "Expected type '" + expectedType + "' but got '" + resolveType(node) + "'"
                        ));
                    }
                }
                case "equals" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check equals"));
                    } else {
                        String actual = node.asText();
                        String exp = String.valueOf(expected);
                        boolean passed = actual.equals(exp);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "equals '" + exp + "' passed"
                                        : "Expected '" + exp + "' but got '" + actual + "'"
                        ));
                    }
                }
                case "notEquals" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check notEquals"));
                    } else {
                        String actual = node.asText();
                        String exp = String.valueOf(expected);
                        boolean passed = !actual.equals(exp);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "notEquals '" + exp + "' passed"
                                        : "Expected value to not equal '" + exp + "' but it did"
                        ));
                    }
                }
                case "contains" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check contains"));
                    } else {
                        String actual = node.asText();
                        String exp = String.valueOf(expected);
                        boolean passed = actual.contains(exp);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "contains '" + exp + "' passed"
                                        : "Expected '" + actual + "' to contain '" + exp + "' but it does not"
                        ));
                    }
                }
                case "greaterThan" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check greaterThan"));
                    } else {
                        try {
                            double actual = node.asDouble();
                            double exp = Double.parseDouble(String.valueOf(expected));
                            boolean passed = actual > exp;
                            results.add(new AssertionResult(path, passed,
                                    passed
                                            ? actual + " > " + exp + " passed"
                                            : "Expected " + actual + " to be greater than " + exp
                            ));
                        } catch (NumberFormatException e) {
                            results.add(new AssertionResult(path, false,
                                    "greaterThan requires a numeric value"));
                        }
                    }
                }
                case "lessThan" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check lessThan"));
                    } else {
                        try {
                            double actual = node.asDouble();
                            double exp = Double.parseDouble(String.valueOf(expected));
                            boolean passed = actual < exp;
                            results.add(new AssertionResult(path, passed,
                                    passed
                                            ? actual + " < " + exp + " passed"
                                            : "Expected " + actual + " to be less than " + exp
                            ));
                        } catch (NumberFormatException e) {
                            results.add(new AssertionResult(path, false,
                                    "lessThan requires a numeric value"));
                        }
                    }
                }
                case "in" -> {
                    if (node == null || node.isNull()) {
                        results.add(new AssertionResult(path, false, "Field is missing, cannot check in"));
                    } else {
                        String actual = node.asText();
                        List<Object> options = (List<Object>) expected;
                        boolean passed = options.stream()
                                .map(String::valueOf)
                                .anyMatch(actual::equals);
                        results.add(new AssertionResult(path, passed,
                                passed
                                        ? "'" + actual + "' is in " + options
                                        : "Expected one of " + options + " but got '" + actual + "'"
                        ));
                    }
                }
                default -> results.add(new AssertionResult(path, false,
                        "Unknown operator: '" + operator + "'"));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolve a dot-notation path in a JsonNode.
     * e.g. "address.city" -> node.get("address").get("city")
     */
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