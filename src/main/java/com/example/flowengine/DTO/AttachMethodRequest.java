package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class AttachMethodRequest {

    @NotNull(message = "methodId is required")
    private Long methodId;

    // Parameter bindings — values or {placeholder} references from previous step responses
    // e.g. {"idList": "1,2,3", "max": "{data.maxAllowed}"}
    // Execution order is assigned automatically by backend (appended after last existing method)
    private Map<String, String> parameterBindings;
}