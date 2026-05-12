package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

@Data
public class EnvironmentRequest {

    @NotBlank
    private String name;

    private Map<String, String> variables; // plain text — encrypted before storing
}