package com.example.flowengine.DTO;

import com.example.flowengine.constants.ExecutionStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Lightweight summary of one module execution run — used for list/history views.
 * Does not include step-level detail.
 */
@Data
public class ModuleExecutionSummaryDTO {

    private Long executionId;
    private Long moduleId;
    private String moduleName;
    private ExecutionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;         // null if still running
    private int totalFlows;
    private int passedFlows;
    private int failedFlows;
    private int skippedSteps;        // total skipped steps across all flows in this run
    private boolean scheduledRun;    // always true for schedule-triggered runs
}