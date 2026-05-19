package com.example.flowengine.DTO;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class FlowStepRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String method;

    private String description;

    @NotBlank
    private String url;

    private String headersJson;

    private String bodyJson;

    private AssertionsRequest assertions;

    private Integer retryCount;      // null = use default (0)
    private Integer retryDelayMs;    // null = use default (1000ms)
    private Integer initialDelayMs;  // null = use default (0ms) — wait before first attempt

    // Polling config — for async/workflow APIs that return 4xx until ready
    private Boolean pollUntilSuccess;   // null = use default (false)
    private Integer pollIntervalMs;     // null = use default (5000ms)
    private Integer pollMaxAttempts;    // null = use default (10)
    private Integer pollExpectedStatus; // null = use default (200)

    @Data
    public static class AssertionsRequest {
        private Integer statusCode;
        private Map<String, Object> schema;

        // JsonNode instead of Map<String, Map<String, Object>> —
        // tolerates any valid JSON structure without type coercion failures
        private JsonNode body;
    }
}