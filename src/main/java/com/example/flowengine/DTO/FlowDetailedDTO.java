package com.example.flowengine.DTO;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class FlowDetailedDTO {
    private Long id;
    private String name;
    private String description;
    private Long moduleId;
    private String moduleName;
    private Long defaultEnvironmentId;
    private List<FlowStepDetailDTO> steps;
    private String flowType;
    private String playwrightScript;

    @Data
    public static class FlowStepDetailDTO {
        private Long id;
        private String name;
        private String description;
        private Integer stepOrder;
        private String method;
        private String url;
        private String headersJson;
        private String bodyJson;
        private Long bodySourceStepId;
        private AssertionsDTO assertions;
        private FlowStepRequest.SkipConditionRequest skipCondition; // null if no skip condition
    }

    @Data
    public static class AssertionsDTO {
        private Integer statusCode;
        private Map<String, Object> schema;
        private Map<String, Map<String, Object>> body;
    }
}
