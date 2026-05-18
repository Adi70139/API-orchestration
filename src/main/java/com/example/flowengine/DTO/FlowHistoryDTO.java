package com.example.flowengine.DTO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full execution history for a flow — list of runs with per-step breakdown.
 */
@Data
public class FlowHistoryDTO {

    private Long flowId;
    private String flowName;
    private int totalRuns;

    // Trend summary across all runs
    private FlowTrendSummary trend;

    // Individual run records, newest first
    private List<RunRecord> runs;

    @Data
    public static class FlowTrendSummary {
        private double passRatePercent;      // 0-100
        private long avgDurationMs;
        private int currentFailStreak;       // consecutive failures from most recent
        private int longestFailStreak;
        private String trend;                // "IMPROVING", "DEGRADING", "STABLE"
    }

    @Data
    public static class RunRecord {
        private Long executionId;
        private String status;               // PASS / FAIL
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
        private Long durationMs;
        private List<StepRunRecord> steps;
    }

    @Data
    public static class StepRunRecord {
        private Long stepId;
        private String stepName;
        private Integer stepOrder;
        private boolean success;
        private Integer statusCode;
        private Long durationMs;
        private String errorMessage;
        private Integer totalAttempts;
    }
}