package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CaptureToEnvRequest {

    @NotNull(message = "environmentId is required")
    private Long environmentId;

    /**
     * Which fields from the step's last response to capture and what to name them
     * as environment variables.
     *
     * field: dot-notation path from poll-fields (e.g. "data.token", "tokenExtractor.result")
     * envVarName: the key name in the environment (e.g. "AUTH_TOKEN")
     *
     * Example:
     * [
     *   { "field": "data.token",  "envVarName": "AUTH_TOKEN" },
     *   { "field": "data.userId", "envVarName": "CURRENT_USER_ID" }
     * ]
     */
    @NotEmpty(message = "At least one field mapping is required")
    private List<FieldMapping> mappings;

    @Data
    public static class FieldMapping {
        @NotNull
        private String field;      // dot-notation key from poll-fields response
        @NotNull
        private String envVarName; // name to save it as in the environment
    }
}