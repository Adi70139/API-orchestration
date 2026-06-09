package com.example.flowengine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Uses LLM to analyze a Swagger/OpenAPI spec and determine a meaningful
 * business-flow order for the endpoints — instead of the arbitrary order
 * they appear in the spec.
 *
 * Input:  list of endpoints with method + path + summary/description
 * Output: ordered list of endpoint keys in logical workflow sequence
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwaggerFlowOrderingService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

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

    /**
     * Given a parsed OpenAPI spec, returns endpoint keys ordered by business flow.
     *
     * @param spec       parsed OpenAPI JsonNode
     * @param endpoints  map of "METHOD:PATH" → { summary, description, tags }
     * @return ordered list of "METHOD:PATH" keys
     */
    public List<String> orderByBusinessFlow(JsonNode spec, Map<String, EndpointInfo> endpoints) {
        if (endpoints.isEmpty()) return List.of();
        if (endpoints.size() == 1) return new ArrayList<>(endpoints.keySet());

        try {
            String prompt = buildPrompt(spec, endpoints);
            String llmResponse = "groq".equals(llmProvider)
                    ? callGroq(prompt)
                    : callOllama(prompt);

            log.info("LLM flow ordering response: {}", llmResponse);
            List<String> ordered = parseOrderResponse(llmResponse, endpoints);

            // Validate — ensure all endpoints are present (LLM might miss some)
            List<String> result = new ArrayList<>(ordered);
            for (String key : endpoints.keySet()) {
                if (!result.contains(key)) {
                    log.warn("LLM missed endpoint '{}' — appending at end", key);
                    result.add(key);
                }
            }
            return result;

        } catch (Exception e) {
            log.warn("LLM flow ordering failed: {} — falling back to original order", e.getMessage());
            return new ArrayList<>(endpoints.keySet());
        }
    }

    // ─── Prompt ───────────────────────────────────────────────────────────────

    private String buildPrompt(JsonNode spec, Map<String, EndpointInfo> endpoints) {
        String apiTitle = spec.path("info").path("title").asText("API");
        String apiDescription = spec.path("info").path("description").asText("");

        StringBuilder endpointList = new StringBuilder();
        int i = 1;
        for (Map.Entry<String, EndpointInfo> entry : endpoints.entrySet()) {
            EndpointInfo info = entry.getValue();
            endpointList.append(i++).append(". KEY: ").append(entry.getKey()).append("\n");
            endpointList.append("   Summary: ").append(info.summary()).append("\n");
            if (info.description() != null && !info.description().isBlank()) {
                endpointList.append("   Description: ").append(info.description()).append("\n");
            }
            if (info.tags() != null && !info.tags().isEmpty()) {
                endpointList.append("   Tags: ").append(String.join(", ", info.tags())).append("\n");
            }
            endpointList.append("\n");
        }

        return """
            You are an API workflow analyst. Given a list of API endpoints, determine the correct business process order.

            API: %s
            %s

            ENDPOINTS:
            %s

            TASK: Order these endpoints to represent a realistic end-to-end business workflow.
            Consider:
            - What must happen first (e.g. authentication, search/lookup before create)
            - Natural process dependencies (e.g. create customer → create lead → process application → review → underwrite → offer → disburse)
            - GET endpoints typically come before their corresponding POST/PUT (fetch first, then act)
            - Creation endpoints (POST) before update endpoints (PUT) for the same resource

            Return ONLY a JSON array of the KEY values in order, exactly as they appear above.
            No explanation, no markdown, just the raw JSON array.

            Example format:
            ["GET:/api/customers", "POST:/api/leads", "PUT:/api/leads", "POST:/api/applications"]

            Now return the ordered array for the endpoints above:
            """.formatted(apiTitle, apiDescription.isBlank() ? "" : "Description: " + apiDescription, endpointList.toString());
    }

    // ─── Response parser ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> parseOrderResponse(String llmResponse, Map<String, EndpointInfo> endpoints) throws Exception {
        // Strip markdown fences
        String clean = llmResponse
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        // Extract JSON array
        int start = clean.indexOf('[');
        int end = clean.lastIndexOf(']');
        if (start == -1 || end == -1) {
            throw new IllegalArgumentException("No JSON array in LLM response: " + llmResponse);
        }
        clean = clean.substring(start, end + 1);

        List<String> ordered = objectMapper.readValue(clean,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));

        // Validate keys exist
        List<String> valid = new ArrayList<>();
        for (String key : ordered) {
            if (endpoints.containsKey(key)) {
                valid.add(key);
            } else {
                // Try case-insensitive match
                String finalKey = key;
                endpoints.keySet().stream()
                        .filter(k -> k.equalsIgnoreCase(finalKey))
                        .findFirst()
                        .ifPresent(valid::add);
            }
        }
        return valid;
    }

    // ─── LLM calls ────────────────────────────────────────────────────────────

    private String callOllama(String prompt) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("model", ollamaModel, "prompt", prompt, "stream", false));
        Request req = new Request.Builder()
                .url(ollamaBaseUrl + "/api/generate")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = okHttpClient.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("Ollama error: HTTP " + response.code());
            return objectMapper.readTree(response.body().string()).path("response").asText();
        }
    }

    private String callGroq(String prompt) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "model", groqModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", 1024,
                "temperature", 0.1
        ));
        Request req = new Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .post(RequestBody.create(body, JSON))
                .addHeader("Authorization", "Bearer " + groqApiKey)
                .build();
        try (Response response = okHttpClient.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("Groq error: HTTP " + response.code());
            return objectMapper.readTree(response.body().string())
                    .path("choices").get(0).path("message").path("content").asText();
        }
    }

    // ─── DTO ──────────────────────────────────────────────────────────────────

    public record EndpointInfo(String summary, String description, List<String> tags) {}
}