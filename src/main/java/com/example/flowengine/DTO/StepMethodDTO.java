package com.example.flowengine.DTO;

import com.example.flowengine.constants.BuiltinMethodType;
import com.example.flowengine.constants.MethodType;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class StepMethodDTO {
    private Long stepMethodId;     // ID of the StepMethod join record — used for detach
    private Long methodId;
    private String methodName;
    private String methodDescription;
    private MethodType methodType;
    private BuiltinMethodType builtinType;
    private boolean global;
    private Integer executionOrder;
    private Map<String, String> parameterBindings;
    private List<CustomMethodDTO.ParameterDefinitionDTO> parameters;
}