package com.example.flowengine.service;

import com.example.flowengine.DTO.MethodExecutionResult;
import com.example.flowengine.constants.BuiltinMethodType;
import com.example.flowengine.entity.CustomMethod;
import com.example.flowengine.entity.StepMethod;
import com.example.flowengine.repository.StepMethodRepository;
import com.example.flowengine.utils.PlaceholderUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MethodExecutorService {

    private final StepMethodRepository stepMethodRepository;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    /**
     * Runs all methods attached to a step in executionOrder.
     * Returns a merged map of all method outputs — keys prefixed as "method.key".
     * Injected into previousResponses so step can use {method.key} placeholders.
     *
     * @param stepId           the step about to execute
     * @param previousResponses accumulated responses from prior steps (for placeholder resolution in bindings)
     * @return list of results (one per method), and a merged JSON string ready to add to previousResponses
     */
    public StepMethodContext runMethodsForStep(Long stepId, List<String> previousResponses) {
        List<StepMethod> stepMethods = stepMethodRepository.findByStepIdOrderByExecutionOrder(stepId);
        List<MethodExecutionResult> results = new ArrayList<>();
        Map<String, String> mergedOutput = new LinkedHashMap<>();

        for (StepMethod sm : stepMethods) {
            CustomMethod method = sm.getMethod();
            long start = System.currentTimeMillis();
            MethodExecutionResult result = new MethodExecutionResult();
            result.setMethodId(method.getId());
            result.setMethodName(method.getName());

            try {
                // Resolve parameter bindings — values may contain {placeholders}
                Map<String, String> resolvedParams = resolveBindings(sm.getParameterBindingsJson(), previousResponses);

                Map<String, String> output = switch (method.getType()) {
                    case BUILTIN -> runBuiltin(method.getBuiltinType(), resolvedParams, false);
                    case USER_DEFINED -> runGroovy(method.getGroovyScript(), resolvedParams);
                };

                result.setOutput(output);
                result.setSuccess(true);
                result.setDurationMs(System.currentTimeMillis() - start);

                // Use camelCase method name as the namespace prefix.
                // e.g. method named "Custom Number Picker" → {customNumberPicker.result}
                // This is unambiguous even when multiple methods share the same output key.
                String camelName = toCamelCase(method.getName());

                List<String> hints = new ArrayList<>();
                output.forEach((k, v) -> {
                    String key = camelName + "." + k;
                    mergedOutput.put(key, v);
                    String preview = v != null && v.length() > 40 ? v.substring(0, 40) + "..." : v;
                    hints.add(String.format("Use {%s} → \"%s\"", key, preview));
                });
                result.setUsageHints(hints);

                log.info("Method '{}' (→ {}) produced {} key(s): {}",
                        method.getName(), camelName, output.size(), output.keySet());

            } catch (Exception e) {
                log.error("Method '{}' failed: {}", method.getName(), e.getMessage());
                result.setSuccess(false);
                result.setErrorMessage(e.getMessage());
                result.setDurationMs(System.currentTimeMillis() - start);
                // Don't stop — let step execution proceed; unresolved {method.x} will fail naturally
            }

            results.add(result);
        }

        return new StepMethodContext(results, mergedOutput);
    }

    /**
     * Test-run a method with concrete parameter values (no placeholder resolution).
     */
    public MethodExecutionResult testMethod(CustomMethod method, Map<String, String> params) {
        long start = System.currentTimeMillis();
        MethodExecutionResult result = new MethodExecutionResult();
        result.setMethodId(method.getId());
        result.setMethodName(method.getName());

        try {
            Map<String, String> output = switch (method.getType()) {
                case BUILTIN -> runBuiltin(method.getBuiltinType(), params, true);
                case USER_DEFINED -> runGroovy(method.getGroovyScript(), params);
            };
            result.setOutput(output);
            result.setSuccess(true);

            // Generate camelCase placeholder hints matching the runtime behavior
            String camelName = toCamelCase(method.getName());
            List<String> hints = new ArrayList<>();
            output.forEach((key, value) -> {
                String placeholder = "{" + camelName + "." + key + "}";
                String preview = value != null && value.length() > 50
                        ? value.substring(0, 50) + "..." : value;
                hints.add(String.format("Use %s in your step URL, headers, or body → \"%s\"",
                        placeholder, preview));
            });
            if (hints.isEmpty()) {
                hints.add("Method produced no output keys. Make sure your script returns a Map.");
            }
            result.setUsageHints(hints);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
        }
        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }

    // ─── Builtin implementations ──────────────────────────────────────────────

    /**
     * Converts a method name to camelCase for use as a placeholder prefix.
     * "Custom Number Picker" → "customNumberPicker"
     * "my-token-gen"         → "myTokenGen"
     * "UUID Generator"       → "uuidGenerator"
     */
    private String toCamelCase(String name) {
        if (name == null || name.isBlank()) return "method";
        String[] words = name.trim().split("[\\s\\-_]+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i].replaceAll("[^a-zA-Z0-9]", "");
            if (w.isEmpty()) continue;
            if (i == 0) sb.append(Character.toLowerCase(w.charAt(0)))
                    .append(w.length() > 1 ? w.substring(1).toLowerCase() : "");
            else sb.append(Character.toUpperCase(w.charAt(0)))
                    .append(w.length() > 1 ? w.substring(1).toLowerCase() : "");
        }
        return sb.isEmpty() ? "method" : sb.toString();
    }

    private Map<String, String> runBuiltin(BuiltinMethodType type, Map<String, String> params, boolean testRun) {
        return switch (type) {
            case RANDOM_NUMBER -> {
                int min = Integer.parseInt(params.getOrDefault("min", "1"));
                int max = Integer.parseInt(params.getOrDefault("max", "100"));
                if (min >= max) throw new IllegalArgumentException("min must be less than max");
                int result = new Random().nextInt(max - min + 1) + min;
                yield Map.of("result", String.valueOf(result));
            }
            case RANDOM_UUID -> Map.of("result", UUID.randomUUID().toString());
            case TIMESTAMP -> {
                String format = params.getOrDefault("format", "yyyy-MM-dd'T'HH:mm:ss");
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern(format));
                yield Map.of("result", ts);
            }
            case STRING_CONCAT -> {
                String values = params.getOrDefault("values", "");
                String separator = params.getOrDefault("separator", "");
                String result = String.join(separator, Arrays.asList(values.split(",")));
                yield Map.of("result", result.trim());
            }
            case DB_QUERY -> runDbQuery(params, testRun);
        };
    }

    private Map<String, String> runDbQuery(Map<String, String> params, boolean testRun) {
        String connectionString = params.get("connectionString");
        String username = params.get("username");
        String passwordParam = params.get("password");
        String query = params.get("query");

        if (connectionString == null || username == null || passwordParam == null || query == null) {
            throw new IllegalArgumentException("DB_QUERY requires: connectionString, username, password, query");
        }

        // Validate SELECT only
        String trimmed = query.trim().toUpperCase();
        if (!trimmed.startsWith("SELECT")) {
            throw new IllegalArgumentException("Only SELECT queries are allowed");
        }

        String password = resolveDbPassword(passwordParam, testRun);

        log.info("DB_QUERY executing testRun={} url='{}' user='{}' queryPreview='{}'",
                testRun, safeJdbcUrl(connectionString), username, preview(query));
        try (Connection conn = DriverManager.getConnection(connectionString, username, password);
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            // Collect all rows
            List<Map<String, String>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    String colName = meta.getColumnLabel(i).toLowerCase();
                    String value = rs.getString(i);
                    row.put(colName, value != null ? value : "");
                }
                rows.add(row);
            }

            if (rows.isEmpty()) {
                throw new IllegalStateException("DB query returned no rows");
            }

            // Pick a random row
            Map<String, String> selectedRow = rows.get(new Random().nextInt(rows.size()));
            log.info("DB_QUERY returned {} row(s), selected 1 random row with columns: {}",
                    rows.size(), selectedRow.keySet());
            return selectedRow;

        } catch (SQLException e) {
            log.error("DB_QUERY failed for url='{}' user='{}': SQLState={} vendorCode={} message={}",
                    safeJdbcUrl(connectionString), username, e.getSQLState(), e.getErrorCode(), e.getMessage());
            throw new RuntimeException("DB query failed: " + e.getMessage(), e);
        }
    }

    private String resolveDbPassword(String passwordParam, boolean testRun) {
        if (testRun) {
            return passwordParam;
        }
        try {
            return encryptionService.decrypt(passwordParam);
        } catch (RuntimeException e) {
            log.warn("DB_QUERY password is not encrypted or cannot be decrypted; using it as plain text. " +
                    "Use /methods/encrypt-password for stored bindings.");
            return passwordParam;
        }
    }

    private String preview(String value) {
        if (value == null) return "";
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 120 ? singleLine.substring(0, 120) + "..." : singleLine;
    }

    private String safeJdbcUrl(String connectionString) {
        if (connectionString == null) return "";
        return connectionString.replaceAll("(?i)(password|pwd)=([^;?&]+)", "$1=<redacted>");
    }

    // ─── Groovy execution ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, String> runGroovy(String script, Map<String, String> params) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("Groovy script is empty");
        }

        try {
            groovy.lang.Binding binding = new groovy.lang.Binding();
            binding.setVariable("params", buildGroovyParamMap(params));

            groovy.lang.GroovyShell shell = new groovy.lang.GroovyShell(binding);
            Object scriptResult = shell.evaluate(script);

            if (scriptResult == null) {
                throw new IllegalStateException("Groovy script returned null — must return a Map or a single value");
            }

            if (scriptResult instanceof Map) {
                Map<String, String> output = new LinkedHashMap<>();
                ((Map<?, ?>) scriptResult).forEach((k, v) ->
                        output.put(String.valueOf(k), v != null ? String.valueOf(v) : ""));
                return output;
            }

            // Single value — wrap as "result"
            return Map.of("result", String.valueOf(scriptResult));

        } catch (Exception e) {
            throw new RuntimeException("Groovy execution error: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the Map passed into the Groovy binding as `params`.
     * Two things fixed here:
     *  1. Case-insensitive keys — params.get("Headers") and params.get("headers") must both work,
     *     since a script author shouldn't have to match the exact casing declared in the UI's
     *     parameter list, and historically a single casing typo silently returned null with no error.
     *  2. Value normalization — the UI's test form sends every scalar value as JSON-encoded text,
     *     so a String-type param like "url" arrives as the literal 8 characters ["\"https:...\""]
     *     INCLUDING the quote characters, not just the URL. Passed straight to `new URL(...)`,
     *     that throws MalformedURLException: no protocol, because the string literally starts
     *     with a `"` character. Here we JSON-sniff each value: if it parses as a JSON scalar
     *     (string/number/boolean), we unwrap it to the plain value. If it's an object/array
     *     (e.g. a JSON request body) or isn't valid JSON at all (e.g. the Headers field's
     *     "k","v" shorthand), we leave it untouched — scripts that expect raw JSON text or a
     *     custom format keep working exactly as before.
     */
    private Map<String, String> buildGroovyParamMap(Map<String, String> params) {
        Map<String, String> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (params == null) return normalized;
        params.forEach((k, v) -> normalized.put(k, normalizeParamValue(v)));
        return normalized;
    }

    private String normalizeParamValue(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(raw);
            if (node.isValueNode()) {
                // Scalar (string/number/boolean/null) — unwrap to its plain text form.
                return node.isNull() ? "" : node.asText();
            }
            // Object or array (e.g. a JSON request body) — keep the original raw text as-is,
            // scripts handle JSON bodies as text (e.g. JsonSlurper or pass-through).
            return raw;
        } catch (Exception e) {
            // Not valid JSON at all — e.g. a bare URL with no quotes, or the Headers field's
            // "k","v" shorthand. Leave it exactly as received.
            return raw;
        }
    }

    // ─── Param resolution ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, String> resolveBindings(String bindingsJson, List<String> previousResponses) {
        if (bindingsJson == null || bindingsJson.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, String> raw = objectMapper.readValue(bindingsJson, Map.class);
            Map<String, String> resolved = new LinkedHashMap<>();
            raw.forEach((k, v) -> resolved.put(k, PlaceholderUtils.resolveValue(v, previousResponses)));
            return resolved;
        } catch (Exception e) {
            log.warn("Could not parse parameterBindings: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // ─── Context carrier ──────────────────────────────────────────────────────

    public record StepMethodContext(
            List<MethodExecutionResult> results,
            Map<String, String> mergedOutput   // method.key → value, ready to inject as JSON
    ) {}
}