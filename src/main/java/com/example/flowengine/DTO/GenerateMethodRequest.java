package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class GenerateMethodRequest {

    @NotBlank(message = "name is required")
    private String name;

    // Plain English: what the method should do, inputs, output
    // e.g. "Take a list of strings and join them with a dash. Input: values (list of strings). Output: result (joined string)"
    @NotBlank(message = "description is required")
    private String description;

    // Parameter definitions — user declares what params the method needs
    private List<ParameterDefinition> parameters;

    @Data
    public static class ParameterDefinition {
        private String name;
        private String type; // string | number | boolean | list
        private String description;
        private boolean required;
    }
}