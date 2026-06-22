package com.example.flowengine.entity;

import com.example.flowengine.constants.ExecutionStatus;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "flow_executions")
@Data
public class FlowExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id", nullable = false)
    private FlowDefinition flow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_execution_id")
    private ModuleExecution moduleExecution; // null if run standalone

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @OneToMany(mappedBy = "flowExecution", cascade = CascadeType.ALL)
    @OrderBy("stepOrder ASC")
    private List<StepExecution> stepExecutions = new ArrayList<>();
}