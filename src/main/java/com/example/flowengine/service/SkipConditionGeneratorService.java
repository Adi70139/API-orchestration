package com.example.flowengine.service;

import com.example.flowengine.DTO.FlowStepRequest.SkipConditionRequest;
import com.example.flowengine.DTO.FlowStepRequest.SkipConditionRequest.SkipConditionRule;
import com.example.flowengine.DTO.GenerateSkipConditionRequest;
import com.example.flowengine.DTO.GenerateSkipConditionResponse;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.repository.FlowStepRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkipConditionGeneratorService {

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private static final List<String> VALID_OPERATORS = List.of(
            "equals", "notEquals", "contains", "greaterThan", "lessThan", "exists", "in"
    );

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final FlowStepRepository flowStepRepository;

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

    public GenerateSkipConditionResponse generate(GenerateSkipConditionRequest request) {
        try {
            log.info("Generating skip condition for flowId={} targetStepOrder={}", request.getFlowId(), request.getTargetStepOrder());

            // Fetch all steps before targetStepOrder that have a recorded response
            List<FlowStep> priorSteps = flowStepRepository
                    .findByFlowIdOrderByStepOrder(request.getFlowId())
                    .stream()
                    .filter(s -> s.getStepOrder() < request.getTargetStepOrder())
                    .filter(s -> s.getLastResponseBody() != null && !s.getLastResponseBody().isBlank())
                    .toList();

            if (priorSteps.isEmpty()) {
                throw new IllegalArgumentException(
                        "No prior step responses found for flowId=" + request.getFlowId() +
                                " before stepOrder=" + request.getTargetStepOrder() +
                                ". Execute the flow at least once so responses are recorded.");
            }

            // Build labeled map: "Step 1 - Fetch User" -> responseBody
            Map<String, String> stepResponses = new LinkedHashMap<>();
            for (FlowStep step : priorSteps) {
                String label = "Step " + step.getStepOrder() + " - " + step.getName();
                stepResponses.put(label, step.getLastResponseBody());
            }

            log.info("Building prompt from {} prior step response(s)", stepResponses.size());
            String prompt = buildPrompt(stepResponses, request.getDescription());

            String llmResponse = "groq".equals(llmProvider)
                    ? callGroq(prompt)
                    : callOllama(prompt);

            log.info("Raw LLM response: {}", llmResponse);
            return parseResponse(llmResponse);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate skip condition: {}", e.getMessage());
            throw new RuntimeException("Failed to generate skip condition: " + e.getMessage());
        }
    }

    // ─── Prompt ──────────────────────────────────────────────────────────────

    private String buildPrompt(Map<String, String> stepResponses, String description) {
        // Build labeled response block so the LLM knows which field came from which step
        StringBuilder responsesBlock = new StringBuilder();
        stepResponses.forEach((label, body) -> {
            responsesBlock.append("[").append(label).append("]\n").append(body).append("\n\n");
        });

        return """
            You are a skip condition generator for an API orchestration engine.
            A "skip condition" decides whether a future step should be bypassed based on values from previous API responses.

            PREVIOUS STEP RESPONSE(S):
            %s
            USER INSTRUCTION (plain English):
            %s

            Your job: translate the instruction into a skip condition JSON object using ONLY the fields visible in the responses above.
            When referencing a field, use its dot-notation path exactly as it appears in the response body (ignore the step label in path).

            OUTPUT FORMAT — return ONLY this raw JSON, no explanation, no markdown, no extra text:
            {
              "logic": "AND",
              "conditions": [
                { "path": "fieldName", "operator": "equals", "value": "someValue" }
              ],
              "explanation": "one sentence describing what this condition does"
            }

            RULES:
            1. "logic" must be exactly "AND" or "OR"
               - AND = ALL conditions must be true to skip the step
               - OR  = ANY one condition being true skips the step
               - Use OR when the user says "or", "either", "any of"
               - Use AND when the user says "and", "both", "all of", or implies multiple must hold simultaneously

            2. "path" must use dot-notation for nested fields
               - e.g. "order.status", "user.address.city", "id", "retryCount"
               - Extract the exact field name from the response body provided

            3. "operator" must be EXACTLY one of these — never invent new ones:
               - "equals"      → field value exactly matches (use for exact string/number match)
               - "notEquals"   → field value does NOT match
               - "contains"    → string field contains a substring
               - "greaterThan" → numeric field is greater than value
               - "lessThan"    → numeric field is less than value
               - "exists"      → field is present (value: "true") or absent (value: "false")
               - "in"          → field value is one of a comma-separated list e.g. "CANCELLED,REFUNDED"

            4. "value" is always a string — even for numbers write "42" not 42
               - For "in" operator: comma-separated string e.g. "CANCELLED,REFUNDED,CLOSED"
               - For "exists" operator: "true" or "false"
               - Infer the value from the user's description; if a specific value is mentioned use it exactly

            5. If the user's intent is ambiguous between AND/OR, default to OR

            6. Only reference fields that actually exist in the response bodies provided

            7. The "explanation" field is a single plain-English sentence summarising what the condition does

            OPERATOR SELECTION GUIDE:
            - "is X" / "equals X" / "= X"              → equals
            - "is not X" / "not equal"                  → notEquals
            - "contains" / "includes" / "has substring" → contains
            - "more than" / "greater than" / "> N"      → greaterThan
            - "less than" / "fewer than" / "< N"        → lessThan
            - "exists" / "is present" / "is missing"    → exists
            - "is one of" / "any of" / "either X or Y"  → in (single condition, comma-separated value)

            EXAMPLES:

            Responses:
            [step1]
            {"id": 1, "status": "CANCELLED", "retryCount": 5}

            Instruction: "skip if status is cancelled"
            Output:
            {
              "logic": "AND",
              "conditions": [
                { "path": "status", "operator": "equals", "value": "CANCELLED" }
              ],
              "explanation": "Skip this step when the status field equals CANCELLED."
            }

            Responses:
            [step1]
            {"order": {"status": "REFUNDED"}}
            [step2]
            {"user": {"tier": "FREE"}}

            Instruction: "skip if order status is refunded or user tier is free"
            Output:
            {
              "logic": "OR",
              "conditions": [
                { "path": "order.status", "operator": "equals", "value": "REFUNDED" },
                { "path": "user.tier",    "operator": "equals", "value": "FREE" }
              ],
              "explanation": "Skip this step when the order is refunded OR the user is on the FREE tier."
            }

            Responses:
            [step1]
            {"retryCount": 4}
            [step2]
            {"errorCode": "TIMEOUT"}

            Instruction: "skip if retry count is more than 3 and error is timeout"
            Output:
            {
              "logic": "AND",
              "conditions": [
                { "path": "retryCount", "operator": "greaterThan", "value": "3" },
                { "path": "errorCode",  "operator": "equals",      "value": "TIMEOUT" }
              ],
              "explanation": "Skip this step only when retryCount exceeds 3 AND the error is TIMEOUT."
            }

            Now generate the skip condition for the input above. Return ONLY the raw JSON object.
            """.formatted(responsesBlock.toString(), description);
    }

    // ─── LLM Callers (identical pattern to AssertionGeneratorService) ────────

    private String callOllama(String prompt) throws Exception {
        log.info("Calling Ollama at {} with model {}", ollamaBaseUrl, ollamaModel);
        String requestBody = objectMapper.writeValueAsString(
                Map.of("model", ollamaModel, "prompt", prompt, "stream", false)
        );
        Request httpRequest = new Request.Builder()
                .url(ollamaBaseUrl + "/api/generate")
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = okHttpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("Ollama API error: HTTP " + response.code() + " — " + err);
            }
            String body = response.body().string();
            return objectMapper.readTree(body).path("response").asText();
        }
    }

    private String callGroq(String prompt) throws Exception {
        log.info("Calling Groq with model {}", groqModel);
        String requestBody = objectMapper.writeValueAsString(
                Map.of(
                        "model", groqModel,
                        "messages", List.of(Map.of("role", "user", "content", prompt)),
                        "max_tokens", 1024,
                        "temperature", 0.1
                )
        );
        Request httpRequest = new Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .addHeader("Authorization", "Bearer " + groqApiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = okHttpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new RuntimeException("Groq API error: HTTP " + response.code() + " — " + err);
            }
            String body = response.body().string();
            return objectMapper.readTree(body)
                    .path("choices").get(0).path("message").path("content").asText();
        }
    }

    // ─── Parser ──────────────────────────────────────────────────────────────

    private GenerateSkipConditionResponse parseResponse(String llmResponse) throws Exception {
        String clean = llmResponse
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .replaceAll("\\bNone\\b", "null")
                .replaceAll("\\bTrue\\b", "true")
                .replaceAll("\\bFalse\\b", "false")
                .replaceAll("'", "\"")
                .trim();

        int start = clean.indexOf('{');
        int end   = clean.lastIndexOf('}');
        if (start == -1 || end == -1) {
            throw new RuntimeException("LLM did not return valid JSON: " + llmResponse);
        }
        clean = clean.substring(start, end + 1);

        // Auto-close missing braces
        long open  = clean.chars().filter(c -> c == '{').count();
        long close = clean.chars().filter(c -> c == '}').count();
        if (open > close) {
            StringBuilder sb = new StringBuilder(clean);
            for (long i = 0; i < (open - close); i++) sb.append('}');
            clean = sb.toString();
        }

        log.info("Parsed JSON for skip condition: {}", clean);

        JsonNode root = objectMapper.readTree(clean);

        // Extract explanation before building SkipConditionRequest
        String explanation = root.path("explanation").asText("Generated skip condition.");

        // Build SkipConditionRequest
        SkipConditionRequest skipCondition = new SkipConditionRequest();

        String logic = root.path("logic").asText("AND").toUpperCase();
        if (!"AND".equals(logic) && !"OR".equals(logic)) logic = "AND"; // guard
        skipCondition.setLogic(logic);

        List<SkipConditionRule> rules = new ArrayList<>();
        JsonNode conditionsNode = root.path("conditions");
        if (conditionsNode.isArray()) {
            for (JsonNode node : conditionsNode) {
                String path     = node.path("path").asText("").trim();
                String operator = node.path("operator").asText("").trim();
                String value    = node.path("value").asText("").trim();

                if (path.isEmpty() || operator.isEmpty()) {
                    log.warn("Skipping malformed condition node: {}", node);
                    continue;
                }

                // Guard: reject unknown operators
                if (!VALID_OPERATORS.contains(operator)) {
                    log.warn("Unknown operator '{}' — skipping condition", operator);
                    continue;
                }

                SkipConditionRule rule = new SkipConditionRule();
                rule.setPath(path);
                rule.setOperator(operator);
                rule.setValue(value);
                rules.add(rule);
            }
        }

        if (rules.isEmpty()) {
            throw new RuntimeException("LLM returned no valid conditions. Raw response: " + llmResponse);
        }

        skipCondition.setConditions(rules);

        GenerateSkipConditionResponse response = new GenerateSkipConditionResponse();
        response.setSkipCondition(skipCondition);
        response.setExplanation(explanation);
        return response;
    }
}