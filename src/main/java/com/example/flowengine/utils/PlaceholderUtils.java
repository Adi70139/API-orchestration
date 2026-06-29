package com.example.flowengine.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaceholderUtils {

    // Pattern supports both {variableName} and {{variableName}} formats
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{?([a-zA-Z0-9_.]+)\\}\\}?");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlaceholderUtils() {}

    public static Set<String> extractParams(String... sources) {
        Set<String> params = new LinkedHashSet<>();
        for (String source : sources) {
            if (source == null || source.isBlank()) continue;
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(source);
            while (matcher.find()) {
                params.add(matcher.group(1));
            }
        }
        return params;
    }

    /**
     * Resolve placeholders in template using accumulated step responses.
     * previousResponses: list of raw JSON response bodies from prior steps, in order.
     * Later responses take priority over earlier ones (last write wins).
     * Fails fast if any placeholder cannot be resolved.
     * Supports both {variableName} and {{variableName}} formats.
     */
    public static String resolve(String template, List<String> previousResponses) {
        if (template == null || template.isBlank()) return template;

        // Build a flat lookup map from all previous responses (later steps win)
        Map<String, String> resolved = buildLookupMap(previousResponses);

        StringBuffer result = new StringBuffer();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);

        while (matcher.find()) {
            String placeholder = matcher.group(1); // e.g. "user.id" or "id"
            String value = resolved.get(placeholder);
            if (value == null) {
                throw new IllegalArgumentException(
                        "Cannot resolve placeholder '{" + placeholder + "}'. " +
                                "Available keys from previous responses: " + resolved.keySet()
                );
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolve placeholders in a single string value without throwing on failure.
     * Used for assertion values — if a placeholder can't be resolved, returns
     * the original template so the assertion fails with a meaningful mismatch
     * rather than blowing up the whole execution.
     * Supports both {variableName} and {{variableName}} formats.
     */
    public static String resolveValue(String template, List<String> previousResponses) {
        if (template == null || !template.contains("{")) return template;

        Map<String, String> lookup = buildLookupMap(previousResponses);

        StringBuffer result = new StringBuffer();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String value = lookup.get(placeholder);
            if (value != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
            } else {
                // Leave unresolved placeholder as-is — assertion will fail with a clear mismatch
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Flatten all previous step response bodies into a single key->value map.
     * Supports dot-notation paths e.g. "user.id" -> "42"
     * Later responses override earlier ones on key conflict.
     */
    public static Map<String, String> buildLookupMap(List<String> previousResponses) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String responseBody : previousResponses) {
            if (responseBody == null || responseBody.isBlank()) continue;
            try {
                JsonNode root = MAPPER.readTree(responseBody);
                flattenNode(root, "", map);
            } catch (Exception e) {
                // Non-JSON response body — skip it, can't extract values
            }
        }
        return map;
    }

    /**
     * Recursively flatten a JsonNode into dot-notation keys.
     * e.g. {"user": {"id": 1}} -> {"user.id": "1"}
     * Also stores top-level keys directly: {"id": "1"}
     */
    private static void flattenNode(JsonNode node, String prefix, Map<String, String> map) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenNode(entry.getValue(), key, map);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenNode(node.get(i), prefix + "[" + i + "]", map);
            }
        } else {
            // Leaf value — store the raw text first so the top-level key always resolves
            if (!prefix.isEmpty()) {
                map.put(prefix, node.asText());
            }
            // If the value is a JSON string (e.g. a method output field like `content` that
            // contains a serialised API response), also flatten its nested fields under the
            // same prefix so paths like {apiCall.content.accessToken} resolve instead of
            // requiring the caller to know that `content` is an embedded JSON string.
            // Guard: only attempt if it starts with { or [ to avoid wasting time on plain strings.
            if (node.isTextual()) {
                String text = node.asText();
                if (!text.isBlank() && (text.startsWith("{") || text.startsWith("["))) {
                    try {
                        JsonNode embedded = MAPPER.readTree(text);
                        if (embedded.isObject() || embedded.isArray()) {
                            // Flatten the embedded JSON under the same prefix — produces
                            // apiCall.content.accessToken, apiCall.content.userInfo.id, etc.
                            flattenNode(embedded, prefix, map);
                            // Note: the top-level key (apiCall.content) is already in the map
                            // from the put() above so it still resolves to the full JSON string.
                        }
                    } catch (Exception ignored) {
                        // Not valid JSON — leave as plain string value, already stored above
                    }
                }
            }
        }
    }
}