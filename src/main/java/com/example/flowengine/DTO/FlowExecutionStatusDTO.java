package com.example.flowengine.DTO;

import com.example.flowengine.constants.ExecutionStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class FlowExecutionStatusDTO {
    private Long flowExecutionId;
    private Long flowId;
    private String flowName;
    private ExecutionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<StepStatusDTO> steps;

    @Data
    public static class StepStatusDTO {
        private Long stepExecutionId;
        private Long stepId;
        private String stepName;
        private Integer stepOrder;
        private ExecutionStatus status;
        private Integer statusCode;
        private String errorMessage;
        private Long durationMs;
        private Integer totalAttempts;
        private Integer totalPollAttempts;
        private Boolean pollingTimedOut;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
    }
}
