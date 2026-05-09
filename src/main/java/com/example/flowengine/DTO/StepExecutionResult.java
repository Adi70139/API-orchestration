package com.example.flowengine.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
public class StepExecutionResult {
    private Long stepId;
    private String stepName;
    private Integer stepOrder;
    private String resolvedUrl;
    private String resolvedHeadersJson;
    private String resolvedBodyJson;
    private int statusCode;
    private String responseBody;
    private boolean success;
    private String errorMessage;
    private long durationMs;
}
