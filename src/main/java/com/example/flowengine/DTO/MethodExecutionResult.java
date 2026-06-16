package com.example.flowengine.DTO;

import lombok.Data;
import java.util.List;
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

    /**
     * Human-readable instructions showing exactly how to reference each output
     * key in step URLs, headers, and body using {method.key} syntax.
     * Populated after a successful test or execution.
     */
    private List<String> usageHints;
}