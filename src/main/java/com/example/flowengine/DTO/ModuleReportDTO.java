package com.example.flowengine.DTO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ModuleReportDTO {
    private Long moduleExecutionId;
    private String moduleName;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Integer totalFlows;
    private Integer passedFlows;
    private Integer failedFlows;
    private List<FlowReportDTO> flows;
}

