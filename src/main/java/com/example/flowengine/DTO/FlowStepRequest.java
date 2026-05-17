package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    private String headersJson; // optional, must be valid JSON object if provided

    private String bodyJson;

    private AssertionsRequest assertions;

    private Integer retryCount;    // null = use default (0)
    private Integer retryDelayMs;

    @Data
    public static class AssertionsRequest {
        private Integer statusCode;                          // optional exact status code check
        private Map<String, Object> schema;                  // optional schema: fieldName -> type
        private Map<String, Map<String, Object>> body;       // optional field assertions
    }     // optional, must be valid JSON if provided
}
