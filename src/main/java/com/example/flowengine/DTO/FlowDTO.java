package com.example.flowengine.DTO;

import lombok.Data;

@Data
public class FlowDTO {
    private Long id;
    private String name;
    private String description;
    private Long moduleId;
    private String moduleName;
    private Long defaultEnvironmentId;
    private Integer stepCount;
}

