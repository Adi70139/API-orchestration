package com.example.flowengine.DTO;

import lombok.Data;
import java.util.List;
import com.example.flowengine.DTO.PollAttemptResult;

import java.util.List;
import com.example.flowengine.DTO.PollAttemptResult;

@Data
public class StepExecutionResult {
    private Long stepId;
    private String stepName;
    private Integer stepOrder;
    private String resolvedUrl;
    private String resolvedHeadersJson;
    private String resolvedBodyJson;
    private Integer statusCode;   // Integer (not int) — can be null on network failure
    private String responseBody;
    private boolean success;
    private String errorMessage;
    private long durationMs;
    private List<AssertionResult> assertionResults;
    private List<RetryAttemptResult> retryAttempts; // null if no retries were made
    private List<PollAttemptResult> pollAttempts;   // null if polling was not enabled
    private Integer totalPollAttempts;
}