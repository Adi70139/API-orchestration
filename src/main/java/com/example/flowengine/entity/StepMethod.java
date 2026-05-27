package com.example.flowengine.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "step_methods")
@Data
public class StepMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", nullable = false)
    private FlowStep step;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "method_id", nullable = false)
    private CustomMethod method;

    // Order in which methods run before the step (1-based)
    @Column(nullable = false)
    private Integer executionOrder = 1;

    // Parameter bindings — JSON map of { paramName: value or {placeholder} }
    // e.g. {"min": "1", "max": "{data.maxRetries}", "query": "SELECT * FROM tokens WHERE active=true"}
    // Values support {placeholder} syntax — resolved against previousResponses at runtime
    @Column(columnDefinition = "TEXT")
    private String parameterBindingsJson;
}