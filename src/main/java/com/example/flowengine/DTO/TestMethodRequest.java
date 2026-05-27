package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class TestMethodRequest {

    @NotNull(message = "methodId is required")
    private Long methodId;

    // Concrete parameter values for this test run — no placeholders, raw values
    // e.g. {"min": "1", "max": "100"} or {"query": "SELECT id, name FROM users LIMIT 1"}
    private Map<String, String> parameters;
}