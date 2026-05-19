package com.example.flowengine.DTO;

import lombok.Data;

@Data
public class PollAttemptResult {
    private int attempt;          // 1-based
    private Integer statusCode;
    private String responseBody;
    private String errorMessage;
    private boolean success;      // true = this poll attempt got the expected status
    private long durationMs;
}