package com.example.flowengine.DTO;

import lombok.Data;
import java.util.Map;

@Data
public class GenerateAssertionsResponse {
    private Integer statusCode;
    private Map<String, Object> schema;
    private Map<String, Map<String, Object>> body;
}