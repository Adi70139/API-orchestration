package com.example.flowengine.DTO;

import lombok.Data;

@Data
public class RetryAttemptResult {
    private int attempt;          // 1-based (1 = original, 2 = first retry, etc.)
    private Integer statusCode;
    private String responseBody;
    private String errorMessage;
    private boolean success;
    private long durationMs;
}