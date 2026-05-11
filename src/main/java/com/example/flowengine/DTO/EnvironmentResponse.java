package com.example.flowengine.DTO;

import lombok.Data;
import java.util.Map;

@Data
public class EnvironmentResponse {
    private Long id;
    private String name;
    private Long moduleId;
    private Map<String, String> variables; // decrypted values
}