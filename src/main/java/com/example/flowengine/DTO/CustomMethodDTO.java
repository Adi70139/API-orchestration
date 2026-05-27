package com.example.flowengine.DTO;

import com.example.flowengine.constants.BuiltinMethodType;
import com.example.flowengine.constants.MethodType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CustomMethodDTO {

    private Long id;
    private String name;
    private String description;
    private MethodType type;
    private BuiltinMethodType builtinType;
    private List<ParameterDefinitionDTO> parameters;
    private String groovyScript;       // only for USER_DEFINED
    private boolean global;
    private LocalDateTime createdAt;

    @Data
    public static class ParameterDefinitionDTO {
        private String name;
        private String type;
        private String description;
        private boolean required;
    }
}