package com.example.flowengine.DTO;

import com.example.flowengine.constants.ExecutionStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full detail of one module execution — flows + steps included.
 * Used for the drill-down view when user clicks a run.
 */
@Data
public class ModuleExecutionDetailDTO {

    private Long executionId;
    private Long moduleId;
    private String moduleName;
    private ExecutionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;

    private List<FlowExecutionDetailDTO> flows;

    @Data
    public static class FlowExecutionDetailDTO {
        private Long flowExecutionId;
        private Long flowId;
        private String flowName;
        private ExecutionStatus status;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private Long durationMs;
        private List<StepExecutionDetailDTO> steps;
    }

    @Data
    public static class StepExecutionDetailDTO {
        private Long stepExecutionId;
        private Long stepId;
        private String stepName;
        private Integer stepOrder;
        private ExecutionStatus status;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private Long durationMs;
        private String resolvedUrl;
        private Integer statusCode;
        private String responseBody;
        private String errorMessage;
        private boolean skipped;
        private String skipReason;     // populated when status = SKIPPED
        private Integer totalAttempts; // > 1 means retries happened
        private List<AssertionResult> assertionResults;
    }
}