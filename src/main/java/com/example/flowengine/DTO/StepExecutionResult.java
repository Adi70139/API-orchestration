package com.example.flowengine.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
public class StepExecutionResult {
    private Long stepId;
    private String stepName;
    private Integer stepOrder;
    private String resolvedUrl;
    private String resolvedHeadersJson;
    private String resolvedBodyJson;
    private Integer statusCode;
    private String responseBody;
    private boolean success;
    private String errorMessage;
    private long durationMs;
    private List<AssertionResult> assertionResults;
    private List<RetryAttemptResult> retryAttempts;
}
