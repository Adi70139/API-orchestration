package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GenerateAssertionsRequest {

    // The step whose lastResponseBody will be used as context for assertion generation
    @NotNull(message = "stepId is required")
    private Long stepId;

    // Plain English description of what to assert
    // e.g. "id should be a number and status should be SUCCESS"
    @NotBlank(message = "description is required")
    private String description;
}