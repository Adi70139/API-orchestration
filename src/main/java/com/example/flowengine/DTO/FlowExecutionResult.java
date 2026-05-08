package com.example.flowengine.DTO;

import lombok.Data;

import java.util.List;

@Data
public class FlowExecutionResult {

    private Long flowId;
    private String flowName;
    private boolean allStepsPassed;
    private List<StepExecutionResult> stepResults;
    private long totalDurationMs;
}
