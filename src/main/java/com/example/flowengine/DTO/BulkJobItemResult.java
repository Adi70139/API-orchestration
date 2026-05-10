package com.example.flowengine.DTO;

import com.example.flowengine.constants.ExecutionStatus;
import lombok.Data;

@Data
public class BulkJobItemResult {
    private Long targetId;
    private String targetName;
    private ExecutionStatus status;
    private Long executionId;
    private Long durationMs;
}