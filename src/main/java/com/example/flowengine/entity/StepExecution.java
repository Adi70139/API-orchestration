package com.example.flowengine.entity;

import com.example.flowengine.constants.ExecutionStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "step_executions")
@Data
public class StepExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_execution_id", nullable = false)
    private FlowExecution flowExecution;

    @Column(nullable = false)
    private Long stepId;

    @Column(nullable = false)
    private String stepName;

    @Column(nullable = false)
    private Integer stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(columnDefinition = "TEXT")
    private String resolvedUrl;

    @Column(columnDefinition = "TEXT")
    private String resolvedHeadersJson;

    @Column(columnDefinition = "TEXT")
    private String resolvedBodyJson;

    private Integer statusCode;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private boolean success;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String assertionResultsJson;

    // Stores all retry attempts as JSON array — null if step succeeded on first try
    @Column(columnDefinition = "TEXT")
    private String retryAttemptsJson;

    // Total number of attempts made (1 = no retries)
    @Column(nullable = false)
    private Integer totalAttempts = 1;

    // Stores all poll attempts as JSON array — null if polling not used
    @Column(columnDefinition = "TEXT")
    private String pollAttemptsJson;

    // Total poll attempts made — null if polling not used
    @Column
    private Integer totalPollAttempts;

    // True if polling exhausted max attempts without getting expected status
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean pollingTimedOut = false;
}
