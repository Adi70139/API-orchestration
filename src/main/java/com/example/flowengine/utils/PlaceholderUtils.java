package com.example.flowengine.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaceholderUtils {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_.]+)}");
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
     * Flatten all previous step response bodies into a single key->value map.
     * Supports dot-notation paths e.g. "user.id" -> "42"
     * Later responses override earlier ones on key conflict.
     */
    private static Map<String, String> buildLookupMap(List<String> previousResponses) {
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
            // Leaf value
            if (!prefix.isEmpty()) {
                map.put(prefix, node.asText());
            }
        }
    }
}