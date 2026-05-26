package com.example.flowengine.service;

import com.example.flowengine.DTO.GenerateAssertionsRequest;
import com.example.flowengine.DTO.GenerateAssertionsResponse;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.repository.FlowStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssertionGeneratorService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final FlowStepRepository flowStepRepository;
    private final SchemaExtractorService schemaExtractorService;

    @Value("${llm.provider:ollama}")
    private String llmProvider;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:deepseek-coder:6.7b}")
    private String ollamaModel;

    @Value("${groq.api.key:}")
    private String groqApiKey;

    @Value("${groq.model:llama-3.1-8b-instant}")
    private String groqModel;

    /** Returns the current cumulative assertions for a step — what's been built up so far. */
    public GenerateAssertionsResponse getCurrentAssertions(Long stepId) {
        FlowStep step = flowStepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));
        return loadExisting(step);
    }

    // ─── Schema generation (deterministic, no LLM) ───────────────────────────

    /**
     * Generates schema assertions for all fields in the step's last response.
     * Merges into existing lastAssertionsJson — never wipes field-level body assertions.
     */
    public GenerateAssertionsResponse generateSchema(Long stepId) {
        FlowStep step = getStepWithResponse(stepId);

        Map<String, String> extractedSchema = schemaExtractorService.extract(step.getLastResponseBody());
        log.info("Extracted {} schema fields for step '{}'", extractedSchema.size(), step.getName());

        // Load existing cumulative assertions
        GenerateAssertionsResponse existing = loadExisting(step);

        // Merge: schema is fully replaced with fresh extraction (source of truth is the response),
        // body assertions are preserved untouched
        existing.setSchema(extractedSchema.isEmpty() ? null : new LinkedHashMap<>(extractedSchema));

        // Persist and return
        saveBack(step, existing);
        return existing;
    }

    // ─── Field-level assertion generation (LLM) ──────────────────────────────

    /**
     * Generates body assertions from natural language description.
     * Sends existing assertions as context to LLM — LLM merges/replaces at field level.
     * Schema (if present) is preserved.
     */
    public GenerateAssertionsResponse generateAssertions(GenerateAssertionsRequest request) {
        try {
            FlowStep step = getStepWithResponse(request.getStepId());

            // Load what we have so far
            GenerateAssertionsResponse existing = loadExisting(step);

            // Field summary for context (not full body — avoids token overload)
            String fieldSummary = schemaExtractorService.buildFieldSummary(step.getLastResponseBody());

            // Serialize existing assertions as context for LLM
            String existingJson = objectMapper.writeValueAsString(existing);

            String prompt = buildPrompt(fieldSummary, existingJson, request.getDescription());
            log.debug("Prompt length: {} chars", prompt.length());

            String llmResponse = "groq".equals(llmProvider)
                    ? callGroq(prompt)
                    : callOllama(prompt);

            log.info("Raw LLM response: {}", llmResponse);
            GenerateAssertionsResponse merged = parseAssertions(llmResponse);

            // Guarantee: schema from existing is never dropped by LLM
            if (merged.getSchema() == null && existing.getSchema() != null) {
                merged.setSchema(existing.getSchema());
            }

            saveBack(step, merged);

            log.info("Merged assertions — schema fields: {}, body assertions: {}",
                    merged.getSchema() != null ? merged.getSchema().size() : 0,
                    merged.getBody() != null ? merged.getBody().size() : 0);

            return merged;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate assertions: {}", e.getMessage());
            throw new RuntimeException("Failed to generate assertions: " + e.getMessage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private FlowStep getStepWithResponse(Long stepId) {
        FlowStep step = flowStepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));
        if (step.getLastResponseBody() == null || step.getLastResponseBody().isBlank()) {
            throw new IllegalArgumentException(
                    "Step '" + step.getName() + "' has no recorded response yet. " +
                            "Execute the flow at least once so a response is available.");
        }
        return step;
    }

    /** Loads lastAssertionsJson into a response object, or returns empty if none yet. */
    private GenerateAssertionsResponse loadExisting(FlowStep step) {
        if (step.getLastAssertionsJson() != null && !step.getLastAssertionsJson().isBlank()) {
            try {
                return objectMapper.readValue(step.getLastAssertionsJson(), GenerateAssertionsResponse.class);
            } catch (Exception e) {
                log.warn("Could not parse existing lastAssertionsJson for step '{}' — starting fresh", step.getName());
            }
        }
        return new GenerateAssertionsResponse();
    }

    /** Serializes the merged result back into lastAssertionsJson on the step. */
    private void saveBack(FlowStep step, GenerateAssertionsResponse result) {
        try {
            step.setLastAssertionsJson(objectMapper.writeValueAsString(result));
            flowStepRepository.save(step);
        } catch (Exception e) {
            log.error("Failed to persist lastAssertionsJson for step {}: {}", step.getId(), e.getMessage());
        }
    }

    // ─── Prompt ──────────────────────────────────────────────────────────────

    private String buildPrompt(String fieldSummary, String existingAssertionsJson, String description) {
        return """
            You are an API test assertion generator.
            Your job is to UPDATE the existing assertions JSON by merging in new body assertions from the user's description.

            STRICT RULES:
            - Return the COMPLETE updated assertions JSON
            - "schema" block: copy it EXACTLY as-is from the existing JSON — do NOT summarize, do NOT write {...}, do NOT change anything
            - "body" block: merge new assertions — replace if same path, add if new path, keep everything else
            - "statusCode": keep existing unless user mentions a specific HTTP status code
            - Never remove existing assertions unless user explicitly says to

            CRITICAL — operator format. Each body assertion is EXACTLY:
              "<path>": { "<operator>": <value> }
            NEVER use: { "type": "equals", "value": "X" } — that is WRONG
            ALWAYS use: { "equals": "X" } — operator IS the key

            OPERATORS — use ONLY these exact strings as keys:
            equals | notEquals | contains | exists | type | greaterThan | lessThan | in

            AVAILABLE FIELDS (path: type):
            %s

            EXISTING ASSERTIONS JSON:
            %s

            USER DESCRIPTION:
            %s

            EXAMPLE of correct body assertion format:
            "userInfo.lastName": { "equals": "Malik" }
            "data.age": { "greaterThan": 18 }
            "data.status": { "in": ["ACTIVE", "PENDING"] }
            "errorCode": { "exists": false }

            Return ONLY the raw updated JSON object — no explanation, no markdown:
            {
              "statusCode": <number or null>,
              "schema": { <exact copy of existing schema> },
              "body": { <merged body assertions> }
            }
            """.formatted(fieldSummary, existingAssertionsJson, description);
    }

    // ─── LLM calls ───────────────────────────────────────────────────────────

    private String callOllama(String prompt) throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                Map.of("model", ollamaModel, "prompt", prompt, "stream", false));
        Request req = new Request.Builder()
                .url(ollamaBaseUrl + "/api/generate")
                .post(RequestBody.create(requestBody, JSON))
                .build();
        try (Response response = okHttpClient.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("Ollama error: HTTP " + response.code());
            return objectMapper.readTree(response.body().string()).path("response").asText();
        }
    }

    private String callGroq(String prompt) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", groqModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", 2048,
                "temperature", 0.1
        ));
        Request req = new Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .post(RequestBody.create(requestBody, JSON))
                .addHeader("Authorization", "Bearer " + groqApiKey)
                .build();
        try (Response response = okHttpClient.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("Groq error: HTTP " + response.code());
            return objectMapper.readTree(response.body().string())
                    .path("choices").get(0).path("message").path("content").asText();
        }
    }

    // ─── Parser ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private GenerateAssertionsResponse parseAssertions(String llmResponse) throws Exception {
        String clean = llmResponse
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .replaceAll("\\bNone\\b", "null")
                .replaceAll("\\bTrue\\b", "true")
                .replaceAll("\\bFalse\\b", "false")
                .replaceAll("'", "\"")
                .replaceAll(",\\s*([}\\]])", "$1") // trailing commas
                .trim();

        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start == -1 || end == -1) throw new RuntimeException("No JSON in LLM response: " + llmResponse);
        clean = clean.substring(start, end + 1);

        // LLM sometimes echoes schema as {...} placeholder — strip it so Jackson doesn't choke
        clean = clean.replaceAll("\"schema\"\\s*:\\s*\\{\\s*\\.{2,}\\s*\\}", "\"schema\":null");
        clean = clean.replaceAll("\"schema\"\\s*:\\s*\\{\\.+\\}", "\"schema\":null");

        // Auto-close unclosed braces (truncation safety net)
        long open = clean.chars().filter(c -> c == '{').count();
        long close = clean.chars().filter(c -> c == '}').count();
        if (open > close) {
            clean = clean + "}".repeat((int)(open - close));
        }

        GenerateAssertionsResponse result = objectMapper.readValue(clean, GenerateAssertionsResponse.class);

        if (result.getStatusCode() != null && result.getStatusCode() == 0) result.setStatusCode(null);

        // Fix LLM using {"type":"equals","value":"X"} instead of {"equals":"X"}
        if (result.getBody() != null) {
            result.getBody().forEach((path, ops) -> {
                if (ops.containsKey("type") && ops.containsKey("value")) {
                    String operator = String.valueOf(ops.get("type"));
                    Object value = ops.get("value");
                    List<String> validOps = List.of("equals","notEquals","contains","exists","type","greaterThan","lessThan","in");
                    if (validOps.contains(operator)) {
                        ops.clear();
                        ops.put(operator, value);
                        log.warn("Fixed malformed operator structure for path '{}': type/value → {}:{}", path, operator, value);
                    }
                }
            });
        }

        // Move any operator objects mistakenly placed in schema → body
        if (result.getSchema() != null) {
            Map<String, Object> cleanSchema = new LinkedHashMap<>();
            result.getSchema().forEach((key, value) -> {
                if (value instanceof String) {
                    cleanSchema.put(key, value);
                } else if (value instanceof Map) {
                    log.warn("Moving operator object from schema to body for key: {}", key);
                    if (result.getBody() == null) result.setBody(new LinkedHashMap<>());
                    result.getBody().put(key, (Map<String, Object>) value);
                }
            });
            result.setSchema(cleanSchema.isEmpty() ? null : cleanSchema);
        }

        // Remove unknown operators
        List<String> validOps = List.of("equals","notEquals","contains","exists","type","greaterThan","lessThan","in");
        if (result.getBody() != null) {
            result.getBody().forEach((path, ops) ->
                    ops.entrySet().removeIf(e -> !validOps.contains(e.getKey())));
            result.getBody().entrySet().removeIf(e -> e.getValue().isEmpty());
        }

        return result;
    }
}