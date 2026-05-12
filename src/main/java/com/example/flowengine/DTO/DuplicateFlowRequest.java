package com.example.flowengine.DTO;

import lombok.Data;

@Data
public class DuplicateFlowRequest {
    private String name;           // optional — defaults to "Copy of {originalName}"
    private Long targetModuleId;   // optional — defaults to same module
}