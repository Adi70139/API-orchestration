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

    @Value("${llm.provider:ollama}") // "ollama" or "groq"
    private String llmProvider;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:deepseek-coder:6.7b}")
    private String ollamaModel;

    @Value("${groq.api.key:}")
    private String groqApiKey;

    @Value("${groq.model:llama-3.1-8b-instant}")
    private String groqModel;

    public GenerateAssertionsResponse generateAssertions(GenerateAssertionsRequest request) {
        try {
            log.info("Generating assertions for stepId={}", request.getStepId());

            FlowStep step = flowStepRepository.findById(request.getStepId())
                    .orElseThrow(() -> new IllegalArgumentException("Step not found: " + request.getStepId()));

            if (step.getLastResponseBody() == null || step.getLastResponseBody().isBlank()) {
                throw new IllegalArgumentException(
                        "Step '" + step.getName() + "' has no recorded response yet. " +
                                "Execute the flow at least once so a response is available.");
            }

            String prompt = buildPrompt(step.getLastResponseBody(), request.getDescription());
            log.debug("Built prompt length: {} chars", prompt.length());

            String llmResponse = "groq".equals(llmProvider)
                    ? callGroq(prompt)
                    : callOllama(prompt);

            log.info("Raw LLM response: {}", llmResponse);
            GenerateAssertionsResponse result = parseAssertions(llmResponse);
            log.info("Parsed assertions — statusCode: {}, schema fields: {}, body assertions: {}",
                    result.getStatusCode(),
                    result.getSchema() != null ? result.getSchema().size() : 0,
                    result.getBody() != null ? result.getBody().size() : 0);
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate assertions: {}", e.getMessage());
            throw new RuntimeException("Failed to generate assertions: " + e.getMessage());
        }
    }


    private String buildPrompt(String responseBody, String description) {
        return """
            You are an API test assertion generator. Given a JSON response body and a plain English description of what to validate, generate a structured assertions object.

            RESPONSE BODY:
            %s

            USER DESCRIPTION:
            %s

            Generate assertions in EXACTLY this JSON format — no explanation, no markdown, no extra text, just the raw JSON object:
            {
              "statusCode": <number or null if not mentioned>,
              "schema": {
                "<fieldName>": "<type: number|string|boolean|array|object>",
                "<nestedObject>": {
                  "<nestedField>": "<type>"
                }
              },
              "body": {
                "<fieldPath>": {
                  "<operator>": <value>
                }
              }
            }

            Available operators for body assertions:
            - "equals": <value>         — exact match
            - "notEquals": <value>      — must not equal
            - "contains": "<string>"    — string contains substring
            - "exists": true/false      — field exists or not
            - "type": "<type>"          — type check
            - "greaterThan": <number>   — numeric greater than
            - "lessThan": <number>      — numeric less than
            - "in": [<value1>, ...]     — value is one of list

            Rules:
            - Use dot notation for nested fields e.g. "address.city"
            - Only include schema if user mentions type/structure validation
            - Only include statusCode if user mentions a specific status code
            - If user says "should exist" use exists:true, "should not exist" use exists:false
            - Infer types from the actual response body values
            - Return ONLY the raw JSON object, nothing else
            - Never use null as a type in schema — skip fields that are null in the response entirely
            - "should not exist" or "should be null" ALWAYS means { "exists": false } — never use { "equals": null }
            - For "should be in X or Y or Z" always use the "in" operator: { "in": ["X", "Y", "Z"] }
            - schema values must ONLY be type strings: number|string|boolean|array|object — never put operators like "in" inside schema
            - Always use full dot notation path — "customer.kycVerified" not just "kycVerified"
            - Valid operators are ONLY: equals, notEquals, contains, exists, type, greaterThan, lessThan, in — never invent new operators
            - Return ONLY the JSON object — no explanation, no description after the closing brace

            EXAMPLE 1:
            Response: {"id": 1, "name": "John", "status": "ACTIVE", "address": {"city": "NYC"}}
            Description: "id should be a number, status should be ACTIVE, city should exist"
            Output:
            {
              "statusCode": null,
              "schema": {
                "id": "number",
                "name": "string"
              },
              "body": {
                "id": { "type": "number" },
                "status": { "equals": "ACTIVE" },
                "address.city": { "exists": true }
              }
            }

            EXAMPLE 2:
            Response: {"status": "ACTIVE", "errorCode": null, "currency": "USD"}
            Description: "errorCode should not exist, currency should be in USD or EUR"
            Output:
            {
              "statusCode": null,
              "schema": null,
              "body": {
                "errorCode": { "exists": false },
                "currency": { "in": ["USD", "EUR"] }
              }
            }
            """.formatted(responseBody, description);
    }

    private String callOllama(String prompt) throws Exception {
        log.info("Calling Ollama at {} with model {}", ollamaBaseUrl, ollamaModel);
        String requestBody = objectMapper.writeValueAsString(
                Map.of(
                        "model", ollamaModel,
                        "prompt", prompt,
                        "stream", false
                )
        );

        Request httpRequest = new Request.Builder()
                .url(ollamaBaseUrl + "/api/generate")
                .post(RequestBody.create(requestBody, JSON))
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = okHttpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("Ollama returned error: HTTP {} — {}", response.code(), errorBody);
                throw new RuntimeException("Ollama API error: HTTP " + response.code() + " — " + errorBody);
            }

            String responseBody = response.body().string();
            log.debug("Ollama raw HTTP response: {}", responseBody);

            var parsed = objectMapper.readTree(responseBody);
            String text = parsed.path("response").asText();
            log.info("Ollama model response text: {}", text);
            return text;
        }
    }

    private String callGroq(String prompt) throws Exception {
        log.info("Calling Groq with model {}", groqModel);
        String requestBody = objectMapper.writeValueAsString(
                Map.of(
                        "model", groqModel,
                        "messages", List.of(
                                Map.of("role", "user", "content", prompt)
                        ),
                        "max_tokens", 1024,
                        "temperature", 0.1
                )
        );

        Request httpRequest = new Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .post(RequestBody.create(requestBody, JSON))
                .addHeader("Authorization", "Bearer " + groqApiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = okHttpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.error("Groq returned error: HTTP {} — {}", response.code(), errorBody);
                throw new RuntimeException("Groq API error: HTTP " + response.code() + " — " + errorBody);
            }

            String responseBody = response.body().string();
            log.debug("Groq raw HTTP response: {}", responseBody);

            var parsed = objectMapper.readTree(responseBody);
            String text = parsed.path("choices").get(0).path("message").path("content").asText();
            log.info("Groq model response text: {}", text);
            return text;
        }
    }

    @SuppressWarnings("unchecked")
    private GenerateAssertionsResponse parseAssertions(String llmResponse) throws Exception {
        log.info("Parsing LLM response, length: {} chars", llmResponse.length());

        String clean = llmResponse
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        // Fix model quirks
        clean = clean.replaceAll("\\bNone\\b", "null");
        clean = clean.replaceAll("\\bTrue\\b", "true");
        clean = clean.replaceAll("\\bFalse\\b", "false");
        clean = clean.replaceAll("'", "\"");
        clean = clean.replaceAll(",\\s*([}\\]])", "$1"); // remove trailing commas before } or ]

        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');

        if (start == -1 || end == -1) {
            log.error("No JSON object found in LLM response: {}", clean);
            throw new RuntimeException("LLM did not return valid JSON: " + llmResponse);
        }

        clean = clean.substring(start, end + 1);
        log.info("Extracted JSON to parse: {}", clean);

        long openBraces = clean.chars().filter(c -> c == '{').count();
        long closeBraces = clean.chars().filter(c -> c == '}').count();
        if (openBraces > closeBraces) {
            long missing = openBraces - closeBraces;
            log.warn("JSON missing {} closing brace(s) — auto-closing", missing);
            StringBuilder sb = new StringBuilder(clean);
            for (int i = 0; i < missing; i++) {
                sb.append("}");
            }
            clean = sb.toString();
        }

        log.info("Final JSON to parse: {}", clean);

        GenerateAssertionsResponse result = objectMapper.readValue(
                clean, GenerateAssertionsResponse.class);

        if (result.getStatusCode() != null && result.getStatusCode() == 0) {
            result.setStatusCode(null);
        }

        // Fix: move operator objects from schema into body
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

        // Fix: remove unknown operators
        List<String> validOperators = List.of(
                "equals", "notEquals", "contains", "exists",
                "type", "greaterThan", "lessThan", "in"
        );
        if (result.getBody() != null) {
            result.getBody().forEach((path, operators) -> {
                List<String> removed = operators.keySet().stream()
                        .filter(k -> !validOperators.contains(k))
                        .toList();
                if (!removed.isEmpty()) {
                    log.warn("Removing unknown operators {} from path '{}'", removed, path);
                }
                operators.entrySet().removeIf(e -> !validOperators.contains(e.getKey()));
            });
            result.getBody().entrySet().removeIf(e -> e.getValue().isEmpty());
        }

        return result;
    }
}