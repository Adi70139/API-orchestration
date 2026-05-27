package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class AttachMethodRequest {

    @NotNull(message = "methodId is required")
    private Long methodId;

    // Execution order among all methods on this step (1-based)
    private Integer executionOrder = 1;

    // Parameter bindings — values or {placeholder} references from previous step responses
    // e.g. {"min": "1", "max": "{data.maxAllowed}", "query": "SELECT token FROM auth WHERE active=true"}
    private Map<String, String> parameterBindings;
}