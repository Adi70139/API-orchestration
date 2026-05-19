package com.example.flowengine.DTO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import com.example.flowengine.DTO.PollAttemptResult;

@Data
public class FlowReportDTO {
    private Long flowId;
    private String flowName;
    private String moduleName;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private List<StepReportDTO> steps;

    @Data
    public static class StepReportDTO {
        private Long stepId;
        private String stepName;
        private Integer stepOrder;
        private String method;
        private String url;
        private String status;
        private Integer statusCode;
        private String responseBody;
        private String errorMessage;
        private Long durationMs;
        private String resolvedUrl;
        private String resolvedHeadersJson;
        private String resolvedBodyJson;
        private Integer totalAttempts;                  // how many retry attempts were made
        private List<RetryAttemptResult> retryAttempts;  // null if only 1 attempt

        private Integer totalPollAttempts;               // null if polling not used
        private boolean pollingTimedOut;
        private List<PollAttemptResult> pollAttempts;    // null if polling not used
    }
}