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
                    case BUILTIN -> runBuiltin(method.getBuiltinType(), resolvedParams);
                    case USER_DEFINED -> runGroovy(method.getGroovyScript(), resolvedParams);
                };

                result.setOutput(output);
                result.setSuccess(true);
                result.setDurationMs(System.currentTimeMillis() - start);

                // Prefix all keys with "method." and merge
                output.forEach((k, v) -> mergedOutput.put("method." + k, v));
                log.info("Method '{}' produced {} output key(s): {}", method.getName(), output.size(), output.keySet());

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
                case BUILTIN -> runBuiltin(method.getBuiltinType(), params);
                case USER_DEFINED -> runGroovy(method.getGroovyScript(), params);
            };
            result.setOutput(output);
            result.setSuccess(true);

            // Generate usage hints — show exactly how to reference each output key
            // in step URLs, headers, and body using {method.key} syntax
            List<String> hints = new ArrayList<>();
            output.forEach((key, value) -> {
                String placeholder = "{method." + key + "}";
                hints.add(String.format(
                        "Use %s in your step URL, headers, or body to get: \"%s\"",
                        placeholder, value.length() > 50 ? value.substring(0, 50) + "..." : value));
            });
            if (hints.isEmpty()) {
                hints.add("Method produced no output keys. Make sure your script returns a Map or a value.");
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

    private Map<String, String> runBuiltin(BuiltinMethodType type, Map<String, String> params) {
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
            case DB_QUERY -> runDbQuery(params);
        };
    }

    private Map<String, String> runDbQuery(Map<String, String> params) {
        String connectionString = params.get("connectionString");
        String username = params.get("username");
        String encryptedPassword = params.get("password");
        String query = params.get("query");

        if (connectionString == null || username == null || encryptedPassword == null || query == null) {
            throw new IllegalArgumentException("DB_QUERY requires: connectionString, username, password, query");
        }

        // Validate SELECT only
        String trimmed = query.trim().toUpperCase();
        if (!trimmed.startsWith("SELECT")) {
            throw new IllegalArgumentException("Only SELECT queries are allowed");
        }

        String password = encryptionService.decrypt(encryptedPassword);

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
            throw new RuntimeException("DB query failed: " + e.getMessage(), e);
        }
    }

    // ─── Groovy execution ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, String> runGroovy(String script, Map<String, String> params) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("Groovy script is empty");
        }

        try {
            groovy.lang.Binding binding = new groovy.lang.Binding();
            // Pass params as a plain LinkedHashMap — guaranteed clean key access in Groovy
            binding.setVariable("params", new java.util.LinkedHashMap<>(params));

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