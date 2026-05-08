package com.example.flowengine.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StepExecutionResult {

    private Long stepId;
    private String stepName;
    private Integer stepOrder;
    private int statusCode;
    private String responseBody;
    private boolean success;
    private String errorMessage; // null if success
    private long durationMs;
}
