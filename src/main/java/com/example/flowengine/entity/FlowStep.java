package com.example.flowengine.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Entity
@Table(name = "flow_steps")
@Data
public class FlowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(nullable = false)
    private Integer stepOrder;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(nullable = true)
    private String description;

    @NotBlank
    @Column(nullable = false)
    private String method;

    @NotBlank
    @Column(nullable = false)
    private String url;

    @Column(columnDefinition = "TEXT")
    private String headersJson;

    @Column(columnDefinition = "TEXT")
    private String bodyJson;

    @Column(columnDefinition = "TEXT")
    private String assertionsJson;

    // Retry config — per step, user-configurable
    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer retryCount = 0;      // 0 = no retries, max enforced at 5

    @Column(columnDefinition = "INTEGER DEFAULT 1000")
    private Integer retryDelayMs = 1000; // delay between retries in ms, max enforced at 10000

    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer initialDelayMs = 0;  // wait before first attempt, max enforced at 30000

    // Polling config — separate from retry, for workflow/async APIs
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean pollUntilSuccess = false;   // enable polling mode

    @Column(columnDefinition = "INTEGER DEFAULT 5000")
    private Integer pollIntervalMs = 5000;      // wait between polls, max enforced at 30000

    @Column(columnDefinition = "INTEGER DEFAULT 10")
    private Integer pollMaxAttempts = 10;       // max polls before giving up, max enforced at 50

    @Column(columnDefinition = "INTEGER DEFAULT 200")
    private Integer pollExpectedStatus = 200;   // HTTP status that means "done"

    @ManyToOne
    @JoinColumn(name = "flow_id", nullable = false)
    @JsonBackReference
    private FlowDefinition flow;
}