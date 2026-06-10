package com.example.flowengine.DTO;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FlowStepRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String method;

    private String description;

    @NotBlank
    private String url;

    private String headersJson;

    private String bodyJson;

    // Build this step's request body from a previous step's response body.
    // bodyJson is deep-merged on top as overrides/new fields.
    private Boolean inheritBodyFromPreviousStep;
    private Long bodySourceStepId; // null = immediate previous step when inheritance is enabled

    private AssertionsRequest assertions;

    private Integer retryCount;      // null = use default (0)
    private Integer retryDelayMs;    // null = use default (1000ms)
    private Integer initialDelayMs;  // null = use default (0ms) — wait before first attempt

    // Polling config — for async/workflow APIs that return 4xx until ready
    private Boolean pollUntilSuccess;   // null = use default (false)
    private Integer pollIntervalMs;     // null = use default (5000ms)
    private Integer pollMaxAttempts;    // null = use default (10)
    private Integer pollExpectedStatus; // null = use default (200)

    // Skip condition — if true against accumulated responses, this step is bypassed
    private SkipConditionRequest skipCondition;

    @Data
    public static class SkipConditionRequest {
        // "AND" = all conditions must match to skip; "OR" = any condition match skips the step
        private String logic = "AND"; // default AND
        private List<SkipConditionRule> conditions;

        @Data
        public static class SkipConditionRule {
            private String path;      // dot-notation key e.g. "order.status" or "status"
            private String operator;  // equals | notEquals | contains | greaterThan | lessThan | exists | in
            private Object value;     // expected value — supports {placeholder} syntax
        }
    }

    @Data
    public static class AssertionsRequest {
        private Integer statusCode;
        private Boolean statusCodeCritical; // null = true (default critical)
        private Map<String, Object> schema;
        private Boolean schemaCritical;     // null = true

        // JsonNode instead of Map<String, Map<String, Object>> —
        // tolerates any valid JSON structure without type coercion failures
        // Each field can include a "critical": false key to make it non-critical
        // e.g. "processId": { "equals": "{processId}", "critical": false }
        private JsonNode body;
    }
}
