package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GenerateSkipConditionRequest {

    // The flow whose steps' lastResponseBody values will be used as context
    @NotNull(message = "flowId is required")
    private Long flowId;

    // Only steps with stepOrder < targetStepOrder are included as context.
    // This ensures conditions can only reference responses that would actually exist at runtime.
    @NotNull(message = "targetStepOrder is required")
    private Integer targetStepOrder;

    // Plain English description of when to skip the step.
    // e.g. "skip if user status is inactive OR if the order total is less than 10"
    @NotBlank(message = "description is required")
    private String description;
}