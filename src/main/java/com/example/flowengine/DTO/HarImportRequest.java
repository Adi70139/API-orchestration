package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class HarImportRequest {

    @NotNull(message = "moduleId is required")
    private Long moduleId;

    // Option 3 — null = create new flow, non-null = append to existing flow
    private Long flowId;

    // Used only when flowId is null (new flow)
    private String flowName;

    // Only import entries matching this domain e.g. "api.myapp.com"
    // null or blank = import all XHR/Fetch entries
    private String filterDomain;

    // If true, response bodies from HAR are stored as lastResponseBody on each step
    // so assertions and skip conditions work immediately without needing to execute first
    private boolean includeResponseAsLastResponse = true;
}