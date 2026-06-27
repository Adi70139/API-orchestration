package com.example.flowengine.controller;

import com.example.flowengine.DTO.DuplicateFlowStepRequest;
import com.example.flowengine.DTO.FlowStepRequest;
import com.example.flowengine.DTO.FlowStepReorderRequest;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.service.FlowStepService;
//import groovy.util.logging.Slf4j;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.example.flowengine.DTO.StepExecutionResult;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/flows/{flowId}/steps")
@RequiredArgsConstructor
@Tag(name = "Flow Steps", description = "Manage steps within a flow")
@Slf4j
public class FlowStepController {

    private final FlowStepService flowStepService;
    private final com.example.flowengine.service.ExecutorService executorService;

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

        return fields;
    }

    @DeleteMapping("/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a step", description = "Delete a step by flow id")
    public void delete(@PathVariable Long flowId,
                       @PathVariable Long stepId) {
        flowStepService.delete(stepId);
    }
}