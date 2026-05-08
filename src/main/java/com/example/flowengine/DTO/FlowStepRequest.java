package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FlowStepRequest {

    @NotBlank
    private String name;

    @NotNull
    private Integer stepOrder;

    @NotBlank
    private String method;

    @NotBlank
    private String url;

    private String headersJson; // optional, must be valid JSON object if provided

    private String bodyJson;    // optional, must be valid JSON if provided
}
