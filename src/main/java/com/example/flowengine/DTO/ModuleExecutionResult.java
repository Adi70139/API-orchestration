package com.example.flowengine.DTO;

import lombok.Data;

import java.util.List;

@Data
public class ModuleExecutionResult {

    private Long moduleId;
    private String moduleName;
    private boolean allFlowsPassed;
    private List<FlowExecutionResult> flowResults;
    private long totalDurationMs;
    private Long moduleExecutionId;
}
