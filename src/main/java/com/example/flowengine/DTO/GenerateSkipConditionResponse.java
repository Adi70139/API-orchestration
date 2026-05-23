package com.example.flowengine.DTO;

import com.example.flowengine.DTO.FlowStepRequest.SkipConditionRequest;
import lombok.Data;

@Data
public class GenerateSkipConditionResponse {
    private SkipConditionRequest skipCondition; // ready to paste directly into a step's skipCondition field
    private String explanation;                 // one-line human-readable summary of what was generated
}