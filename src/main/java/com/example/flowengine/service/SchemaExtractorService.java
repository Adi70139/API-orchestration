package com.example.flowengine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministically extracts a flat schema from a JSON response body.
 * Uses dot-notation for nested fields.
 * Never truncates — no LLM involved.
 *
 * Type mapping:
 *   JsonNode.isNumber()  → "number"
 *   JsonNode.isBoolean() → "boolean"
 *   JsonNode.isArray()   → "array"
 *   JsonNode.isObject()  → "object"
 *   JsonNode.isNull()    → skipped (null fields excluded from schema)
 *   JsonNode.isTextual() → "string" (or "date" if matches ISO date pattern)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaExtractorService {

    private final ObjectMapper objectMapper;

    private static final java.util.regex.Pattern DATE_PATTERN =
            java.util.regex.Pattern.compile(
                    "^\\d{4}-\\d{2}-\\d{2}(T.*)?$|^\\d{2}-\\d{4}$"
            );

    private static final java.util.regex.Pattern TIME_PATTERN =
            java.util.regex.Pattern.compile("^PT\\d+[HMS].*$");

    /**
     * @param responseBodyJson raw JSON string from a step's lastResponseBody
     * @return flat map of dot-notation path → type string, null fields excluded
     */
    public Map<String, String> extract(String responseBodyJson) {
        Map<String, String> schema = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(responseBodyJson);
            flatten("", root, schema);
        } catch (Exception e) {
            log.warn("SchemaExtractorService: could not parse response body — {}", e.getMessage());
        }
        return schema;
    }

    /**
     * Builds a compact field-list string for use in LLM prompts.
     * Format: "path: type" per line — gives the LLM context on field names without
     * sending the full response body (avoids token overload on large responses).
     */
    public String buildFieldSummary(String responseBodyJson) {
        Map<String, String> schema = extract(responseBodyJson);
        if (schema.isEmpty()) return "(no fields extracted)";

        StringBuilder sb = new StringBuilder();
        schema.forEach((path, type) -> sb.append(path).append(": ").append(type).append("\n"));
        return sb.toString();
    }

    private void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull()) {
            // skip null fields — they have no meaningful type
            return;
        }

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.fields().forEachRemaining(entry -> {
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                JsonNode child = entry.getValue();

                if (child.isNull()) {
                    // skip nulls
                } else if (child.isObject()) {
                    out.put(path, "object");
                    flatten(path, child, out); // recurse into nested objects
                } else if (child.isArray()) {
                    out.put(path, "array");
                    // recurse into first array element only (representative schema)
                    if (child.size() > 0 && child.get(0).isObject()) {
                        flatten(path + "[0]", child.get(0), out);
                    }
                } else {
                    out.put(path, inferType(child));
                }
            });
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            if (arr.size() > 0 && arr.get(0).isObject()) {
                flatten(prefix + "[0]", arr.get(0), out);
            }
        }
    }

    private String inferType(JsonNode node) {
        if (node.isNumber())  return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isArray())   return "array";
        if (node.isObject())  return "object";
        if (node.isTextual()) {
            String val = node.asText();
            if (DATE_PATTERN.matcher(val).matches()) return "date";
            if (TIME_PATTERN.matcher(val).matches()) return "time";
            return "string";
        }
        return "string";
    }
}