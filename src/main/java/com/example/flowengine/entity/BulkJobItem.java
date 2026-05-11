package com.example.flowengine.entity;

import com.example.flowengine.constants.ExecutionStatus;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "bulk_job_items")
@Data
public class BulkJobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "bulk_job_id", nullable = false)
    private BulkJob bulkJob;

    @Column(nullable = false)
    private Long targetId; // moduleId or flowId

    @Column(nullable = false)
    private String targetName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status; // RUNNING, PASS, FAIL

    private Long executionId; // moduleExecutionId or flowExecutionId once done

    private Long environmentId; // environment override for this item

    private Long durationMs;
}