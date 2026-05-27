package com.example.flowengine.DTO;

import lombok.Data;
import java.util.Map;

/**
 * Result of running a single method before a step.
 * Output is always a flat map — keys become available as {method.key} placeholders.
 */
@Data
public class MethodExecutionResult {
    private Long methodId;
    private String methodName;
    private boolean success;
    private String errorMessage;
    private long durationMs;
    private Map<String, String> output; // {method.key} → value
}