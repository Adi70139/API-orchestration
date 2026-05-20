package com.example.flowengine.DTO;

import com.example.flowengine.constants.ExecutionStatus;
import lombok.Data;

@Data
public class FlowExecutionStartResponse {
    private Long flowExecutionId;
    private Long flowId;
    private String flowName;
    private ExecutionStatus status;
}
