package com.example.flowengine.entity;

import jakarta.persistence.*;
import lombok.Data;

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
}