package com.example.flowengine.DTO;

import lombok.Data;

import java.util.List;

/**
 * Trend data for a single step across its last N executions.
 * Used by the frontend to render per-step sparklines or trend indicators.
 */
@Data
public class StepTrendDTO {

    private Long stepId;
    private String stepName;
    private Integer stepOrder;

    // Aggregates over the trend window
    private int totalRuns;
    private int passCount;
    private int failCount;
    private double passRatePercent;
    private long avgDurationMs;
    private long minDurationMs;
    private long maxDurationMs;
    private int currentFailStreak;
    private boolean isFlaky;             // pass rate between 20-80% — unreliable step

    // Last N results as a simple pass/fail timeline — for sparkline rendering
    // true = passed, false = failed, ordered oldest → newest
    private List<Boolean> resultTimeline;

    // Most common error message when failing
    private String mostCommonError;
}