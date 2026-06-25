package com.example.flowengine.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.flowengine.DTO.FlowStepRequest;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

@Entity
@Table(name = "flow_steps")
@Data
public class FlowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(nullable = false)
    private Integer stepOrder;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(nullable = true)
    private String description;

    @NotBlank
    @Column(nullable = false)
    private String method;

    @NotBlank
    @Column(nullable = false)
    private String url;

    @Column(columnDefinition = "TEXT")
    private String headersJson;

    @Column(columnDefinition = "TEXT")
    private String bodyJson;

    @Column(name = "body_source_step_id")
    private Long bodySourceStepId;

    /**
     * Alternate request body variants for the same method+URL, captured during
     * HAR/Postman import when multiple distinct payloads hit the same endpoint.
     * JSON array: [{"name": "...", "bodyJson": "..."}]. bodyJson on this step
     * remains the active/default payload (the first variant).
     */
    @JsonIgnore
    @Column(name = "payload_variants_json", columnDefinition = "TEXT")
    private String payloadVariantsJson;

    /**
     * Deserialized payload variants — computed from payloadVariantsJson for JSON serialization.
     * Returned by the API so the frontend gets a proper list, not a raw JSON string.
     */
    @Transient
    public List<FlowStepRequest.PayloadVariant> getPayloadVariants() {
        if (payloadVariantsJson == null || payloadVariantsJson.isBlank()) return null;
        try {
            return new ObjectMapper().readValue(payloadVariantsJson,
                    new TypeReference<List<FlowStepRequest.PayloadVariant>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // Assertions evaluated at execution time — saved explicitly by user
    @Column(columnDefinition = "TEXT")
    private String assertionsJson;

    // Cumulative assertions built up via the assertion generator (schema + field-level).
    // Never overwritten wholesale — always merged. Saved back after each generate call.
    // User explicitly applies this to assertionsJson when satisfied.
    @Column(columnDefinition = "TEXT")
    private String lastAssertionsJson;

    // Retry config — per step, user-configurable
    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer retryCount = 0;      // 0 = no retries, max enforced at 5

    @Column(columnDefinition = "INTEGER DEFAULT 1000")
    private Integer retryDelayMs = 1000; // delay between retries in ms, max enforced at 10000

    @Column(columnDefinition = "INTEGER DEFAULT 0")
    private Integer initialDelayMs = 0;  // wait before first attempt, max enforced at 30000

    // Polling config — separate from retry, for workflow/async APIs
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean pollUntilSuccess = false;   // enable polling mode

    @Column(columnDefinition = "INTEGER DEFAULT 5000")
    private Integer pollIntervalMs = 5000;      // wait between polls, max enforced at 30000

    @Column(columnDefinition = "INTEGER DEFAULT 10")
    private Integer pollMaxAttempts = 10;       // max polls before giving up, max enforced at 50

    @Column(columnDefinition = "INTEGER DEFAULT 200")
    private Integer pollExpectedStatus = 200;   // HTTP status that means "done"

    // Optional — poll until a field in the response body matches a condition.
    // Evaluated IN ADDITION to pollExpectedStatus (both must be satisfied unless
    // pollExpectedStatus is set to 0, which means "any status code is OK").
    //
    // JSON: { "path": "data.status", "operator": "equals", "value": "COMPLETED" }
    // or with multiple: { "logic": "OR", "conditions": [
    //   { "path": "data.status", "operator": "equals", "value": "COMPLETED" },
    //   { "path": "data.status", "operator": "equals", "value": "FAILED" }
    // ]}
    //
    // Supported operators: equals, notEquals, contains, notContains, in, exists, greaterThan, lessThan
    // path uses dot-notation: "data.status", "result[0].state", "status"
    @Column(columnDefinition = "TEXT")
    private String pollConditionJson;

    // Skip condition — evaluated against accumulated responses before this step runs.
    // JSON: { "logic": "AND"|"OR", "conditions": [ { "path": "status", "operator": "equals", "value": "inactive" } ] }
    @Column(columnDefinition = "TEXT")
    private String skipConditionJson;

    // Last successful response body from this step's most recent execution.
    // Updated by ExecutorService after every successful step run.
    // Used by LLM generators for assertions and skip conditions — frontend never needs to send response bodies.
    @Column(columnDefinition = "TEXT")
    private String lastResponseBody;

    @ManyToOne
    @JoinColumn(name = "flow_id", nullable = false)
    @JsonBackReference
    private FlowDefinition flow;
}