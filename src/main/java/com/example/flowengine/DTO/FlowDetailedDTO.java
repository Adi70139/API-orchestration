package com.example.flowengine.DTO;

import lombok.Data;

import java.util.List;

@Data
public class FlowDetailedDTO {
    private Long id;
    private String name;
    private String description;
    private Long moduleId;
    private String moduleName;
    private Long defaultEnvironmentId;
    private List<FlowStepDetailDTO> steps;

    @Data
    public static class FlowStepDetailDTO {
        private Long id;
        private String name;
        private String description;
        private Integer stepOrder;
        private String method;
        private String url;
    }
}

