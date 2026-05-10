package com.example.flowengine.entity;

import com.example.flowengine.constants.BulkJobStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "bulk_jobs")
@Data
public class BulkJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private BulkJobStatus status;

    @Column(nullable = false)
    private String type; // "MODULE" or "FLOW"

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @OneToMany(mappedBy = "bulkJob", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BulkJobItem> items;
}