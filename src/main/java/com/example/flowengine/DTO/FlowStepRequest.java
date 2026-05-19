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

    private Integer retryCount;
    private Integer retryDelayMs;
    private Integer initialDelayMs;  // null = use default (0ms) — wait before first attempt

    @Data
    public static class AssertionsRequest {
        private Integer statusCode;
        private Map<String, Object> schema;

        // JsonNode instead of Map<String, Map<String, Object>> —
        // tolerates any valid JSON structure without type coercion failures
        private JsonNode body;
    }
}