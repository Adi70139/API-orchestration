package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CollectionImportRequest {

    @NotNull
    private Long moduleId;

    @NotBlank
    private String flowName; // user-provided name for the flow
}