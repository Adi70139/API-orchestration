package com.example.flowengine.controller;

import com.example.flowengine.DTO.DuplicateFlowStepRequest;
import com.example.flowengine.DTO.FlowStepRequest;
import com.example.flowengine.DTO.FlowStepReorderRequest;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.service.FlowStepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.example.flowengine.DTO.StepExecutionResult;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/flows/{flowId}/steps")
@RequiredArgsConstructor
@Tag(name = "Flow Steps", description = "Manage steps within a flow")
@Slf4j
public class FlowStepController {

    private final FlowStepService flowStepService;
    private final com.example.flowengine.service.ExecutorService executorService;
    private final com.example.flowengine.service.EnvironmentService environmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a step", description = "Add a new step to a flow")
    public FlowStep create(@PathVariable Long flowId,
                           @Valid @RequestBody FlowStepRequest request) {
        return flowStepService.create(flowId, request);
    }

    @GetMapping
    @Operation(summary = "Get a step", description = "Get a step by flow id")
    public List<FlowStep> getByFlowId(@PathVariable Long flowId) {
        return flowStepService.getByFlowId(flowId);
    }

    @GetMapping("/{stepId}")
    @Operation(summary = "Get a step", description = "Get a step by id")
    public FlowStep getById(@PathVariable Long flowId,
                            @PathVariable Long stepId) {
        // flowId validated implicitly — step's FK ensures it belongs to a flow
        return flowStepService.getById(stepId);
    }

    @PutMapping("/{stepId}")
    @Operation(summary = "Update a step", description = "Updatet a step by flow id")
    public FlowStep update(@PathVariable Long flowId,
                           @PathVariable Long stepId,
                           @Valid @RequestBody FlowStepRequest request) {
        return flowStepService.update(stepId, request);
    }

    @PutMapping("/reorder")
    @Operation(summary = "Reorder steps", description = "Update stepOrder values for steps in a flow")
    public List<FlowStep> reorder(@PathVariable Long flowId,
                                  @Valid @RequestBody FlowStepReorderRequest request) {
        return flowStepService.reorder(flowId, request);
    }

    @PostMapping("/{stepId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Duplicate a step", description = "Duplicate an existing step in the same flow")
    public FlowStep duplicate(@PathVariable Long flowId,
                              @PathVariable Long stepId,
                              @RequestBody(required = false) DuplicateFlowStepRequest request) {
        return flowStepService.duplicate(flowId, stepId, request);
    }

    @PostMapping("/{stepId}/variants/create-step")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a step from a payload variant",
            description = "Promotes one of a step's payload variants (e.g. captured by HAR import when the " +
                    "same endpoint was hit with different bodies) into its own standalone step appended to the flow")
    public FlowStep createStepFromVariant(@PathVariable Long flowId,
                                          @PathVariable Long stepId,
                                          @Valid @RequestBody com.example.flowengine.DTO.CreateStepFromVariantRequest request) {
        return flowStepService.createFromVariant(flowId, stepId, request);
    }

    /**
     * Returns all fields available from the step's last response body, flattened to
     * dot-notation paths. Use this to discover what fields you can use in pollConditionJson
     * instead of manually writing paths.
     *
     * Requires the step to have been executed at least once (lastResponseBody must be set).
     * Run the step/flow once first, then call this endpoint to see available fields.
     *
     * GET /flows/{flowId}/steps/{stepId}/poll-fields
     *
     * Example response:
     * {
     *   "data.status": "PENDING",
     *   "data.processInstanceKey": "2251799830495486",
     *   "data.assignee": "a10038fa-ba87-44d5-9763-b9b1d902590b",
     *   "processState": "ACTIVE"
     * }
     */
    /**
     * Execute a single step in isolation — fires one HTTP request and returns the response.
     * No flow context, no previous-step placeholders resolved (env vars are resolved if envId given).
     * On success, saves the response body to lastResponseBody so poll-fields is immediately usable.
     *
     * POST /flows/{flowId}/steps/{stepId}/run?envId=1
     */
    @PostMapping("/{stepId}/run")
    @Operation(summary = "Run a single step in isolation",
            description = "Fires one HTTP request for this step without running the whole flow. " +
                    "Useful for testing a newly added step, verifying URL/headers/body are correct, " +
                    "and capturing the response so poll-fields can show available condition fields. " +
                    "Placeholders referencing prior steps ({stepName.field}) will be unresolved — " +
                    "if you need those, run the full flow instead.")
    public StepExecutionResult runStep(@PathVariable Long flowId,
                                       @PathVariable Long stepId,
                                       @RequestParam(required = false) Long envId) {
        FlowStep step = flowStepService.getById(stepId);
        if (!step.getFlow().getId().equals(flowId)) {
            throw new IllegalArgumentException("Step " + stepId + " does not belong to flow " + flowId);
        }
        return executorService.runStep(stepId, envId);
    }

    /**
     * Captures specific fields from a step's last response body / method outputs
     * and saves them as environment variables in an existing environment.
     *
     * This is what makes two steps independently testable without running a full flow:
     *   1. POST /{stepId}/run  → step A fires, response captured
     *   2. POST /{stepId}/capture-to-env → pick token/userId/etc → saved to env
     *   3. POST /{stepB}/run?envId=X → step B resolves {AUTH_TOKEN} from env, works standalone
     *
     * POST /flows/{flowId}/steps/{stepId}/capture-to-env
     */
    @PostMapping("/{stepId}/capture-to-env")
    @Operation(summary = "Capture response fields to environment variables",
            description = "Picks specific fields from the step's last response and saves them " +
                    "as env variables — merges into existing vars, does not replace the whole environment. " +
                    "Use poll-fields first to discover available field paths.")
    public com.example.flowengine.DTO.EnvironmentResponse captureToEnv(
            @PathVariable Long flowId,
            @PathVariable Long stepId,
            @Valid @RequestBody com.example.flowengine.DTO.CaptureToEnvRequest request) {

        FlowStep step = flowStepService.getById(stepId);
        if (!step.getFlow().getId().equals(flowId)) {
            throw new IllegalArgumentException("Step " + stepId + " does not belong to flow " + flowId);
        }

        // Build the same merged field map that poll-fields returns
        Map<String, String> allFields = new java.util.LinkedHashMap<>();
        if (step.getLastResponseBody() != null && !step.getLastResponseBody().isBlank()) {
            allFields.putAll(com.example.flowengine.utils.PlaceholderUtils
                    .buildLookupMap(java.util.List.of(step.getLastResponseBody())));
        }
        if (step.getLastMethodOutputsJson() != null && !step.getLastMethodOutputsJson().isBlank()) {
            try {
                Map<?, ?> methodFields = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(step.getLastMethodOutputsJson(), Map.class);
                methodFields.forEach((k, v) -> allFields.put(String.valueOf(k), v != null ? String.valueOf(v) : ""));
            } catch (Exception ignored) {}
        }

        if (allFields.isEmpty()) {
            throw new IllegalStateException(
                    "Step '" + step.getName() + "' has no captured response yet. " +
                            "Run it first with POST /{stepId}/run, then capture fields.");
        }

        // Resolve each requested mapping
        Map<String, String> toCapture = new java.util.LinkedHashMap<>();
        List<String> notFound = new java.util.ArrayList<>();
        for (com.example.flowengine.DTO.CaptureToEnvRequest.FieldMapping mapping : request.getMappings()) {
            String value = allFields.get(mapping.getField());

            if (value == null) {
                // Field not found as a scalar — try indexed array keys first (field[0], field[1]...)
                List<String> arrayValues = new java.util.ArrayList<>();
                int i = 0;
                while (true) {
                    String v = allFields.get(mapping.getField() + "[" + i + "]");
                    if (v == null) break;
                    arrayValues.add(v);
                    i++;
                }

                if (!arrayValues.isEmpty()) {
                    value = arrayValues.size() == 1 ? arrayValues.get(0) : arrayValues.toString();
                } else {
                    // No indexed keys either — navigate the raw JSON to check if the path exists
                    // but is an empty array ([] produces no indexed keys so the fallback above
                    // finds nothing, which incorrectly maps to "field not found")
                    value = resolveRawJsonPath(step.getLastResponseBody(), mapping.getField());
                }
            }

            if (value == null) {
                notFound.add(mapping.getField());
            } else {
                toCapture.put(mapping.getEnvVarName(), value);
            }
        }

        if (!notFound.isEmpty()) {
            throw new IllegalArgumentException(
                    "Field(s) not found in step response: " + notFound +
                            ". Call poll-fields to see all available paths.");
        }

        com.example.flowengine.DTO.EnvironmentResponse updated =
                environmentService.mergeVariables(request.getEnvironmentId(), toCapture);

        log.info("[CaptureToEnv] Captured {} field(s) from step '{}' to environment id={}: {}",
                toCapture.size(), step.getName(), request.getEnvironmentId(), toCapture.keySet());

        return updated;
    }

    @GetMapping("/{stepId}/poll-fields")
    @Operation(summary = "Get available poll condition fields",
            description = "Returns all flattened dot-notation fields from the step's last response body " +
                    "AND method outputs from attached custom methods. Run the step at least once first.")
    public Map<String, String> getPollFields(@PathVariable Long flowId,
                                             @PathVariable Long stepId) {
        FlowStep step = flowStepService.getById(stepId);
        if (!step.getFlow().getId().equals(flowId)) {
            throw new IllegalArgumentException("Step " + stepId + " does not belong to flow " + flowId);
        }

        Map<String, String> fields = new java.util.LinkedHashMap<>();

        // Response body fields
        if (step.getLastResponseBody() != null && !step.getLastResponseBody().isBlank()) {
            fields.putAll(com.example.flowengine.utils.PlaceholderUtils
                    .buildLookupMap(java.util.List.of(step.getLastResponseBody())));
        }

        // Method output fields — stored as already-flattened JSON map
        if (step.getLastMethodOutputsJson() != null && !step.getLastMethodOutputsJson().isBlank()) {
            try {
                Map<?, ?> methodFields = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(step.getLastMethodOutputsJson(), Map.class);
                methodFields.forEach((k, v) -> fields.put(String.valueOf(k), v != null ? String.valueOf(v) : ""));
            } catch (Exception e) {
                log.warn("Could not parse lastMethodOutputsJson for step {}: {}", stepId, e.getMessage());
            }
        }

        if (fields.isEmpty()) {
            throw new IllegalStateException(
                    "Step '" + step.getName() + "' has no recorded fields yet. " +
                            "Run this step once using POST /{stepId}/run, then call this endpoint.");
        }

        // Collapse array index keys back to their base path for cleaner UI display.
        Map<String, String> collapsed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (key.matches(".*\\[\\d+]$")) {
                String base = key.replaceAll("\\[\\d+]$", "");
                if (!collapsed.containsKey(base)) {
                    collapsed.put(base, "[array] " + entry.getValue());
                }
            } else {
                collapsed.put(key, entry.getValue());
            }
        }

        // Empty arrays produce no indexed keys so they're invisible in the flattened map.
        // Walk the raw JSON to find any array nodes that aren't already represented.
        if (step.getLastResponseBody() != null && !step.getLastResponseBody().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode root =
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(step.getLastResponseBody());
                addEmptyArrayPaths(root, "", collapsed);
            } catch (Exception ignored) {}
        }

        return collapsed;
    }

    private void addEmptyArrayPaths(com.fasterxml.jackson.databind.JsonNode node,
                                    String prefix,
                                    Map<String, String> target) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                addEmptyArrayPaths(entry.getValue(), path, target);
            });
        } else if (node.isArray() && node.size() == 0) {
            // Empty array — add with [] marker so user knows the field exists but is empty
            if (!target.containsKey(prefix)) {
                target.put(prefix, "[empty array]");
            }
        }
    }


/**
 * Navigates a raw JSON string using dot-notation path and returns the value as a string.
 * Handles cases the flattened map can't express:
 *   - Empty arrays: [] → returns "[]" so the field is recognised as existing
 *   - Null values: null → returns "" (empty string, not null, so capture succeeds)
 * Returns null only if the path genuinely doesn't exist in the JSON.
 */
private String resolveRawJsonPath(String json, String dotPath) {
    if (json == null || json.isBlank() || dotPath == null) return null;
    try {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        for (String segment : dotPath.split("\\.")) {
            if (node == null || node.isMissingNode()) return null;
            node = node.isObject() ? node.get(segment) : null;
        }
        if (node == null || node.isMissingNode()) return null;
        if (node.isNull())  return "";
        if (node.isArray()) return node.size() == 0 ? "[]" : node.toString();
        if (node.isValueNode()) return node.asText();
        return node.toString(); // nested object
    } catch (Exception e) {
        return null;
    }
}

@DeleteMapping("/{stepId}")
@ResponseStatus(HttpStatus.NO_CONTENT)
@Operation(summary = "Delete a step", description = "Delete a step by flow id")
public void delete(@PathVariable Long flowId,
                   @PathVariable Long stepId) {
    flowStepService.delete(stepId);
}
}