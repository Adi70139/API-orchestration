package com.example.flowengine.DTO;

import lombok.Data;
import java.util.List;

@Data
public class EditMethodRequest {
    private String name;
    private String description;
    private List<GenerateMethodRequest.ParameterDefinition> parameters;
    // If provided, replaces script directly (user manually edited it)
    // If null, LLM regenerates from updated description
    private String groovyScript;
}