package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GenerateAssertionsRequest {

    @NotBlank
    private String responseBody; // actual JSON response from the step

    @NotBlank
    private String description;  // plain English e.g. "id should be a number and status should be SUCCESS"
}