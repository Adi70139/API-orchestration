package com.example.flowengine.controller;

import com.example.flowengine.DTO.DuplicateFlowStepRequest;
import com.example.flowengine.DTO.FlowStepRequest;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.service.FlowStepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/flows/{flowId}/steps")
@RequiredArgsConstructor
@Tag(name = "Flow Steps", description = "Manage steps within a flow")
public class FlowStepController {

    private final FlowStepService flowStepService;

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

    @PostMapping("/{stepId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Duplicate a step", description = "Duplicate an existing step in the same flow")
    public FlowStep duplicate(@PathVariable Long flowId,
                              @PathVariable Long stepId,
                              @RequestBody(required = false) DuplicateFlowStepRequest request) {
        return flowStepService.duplicate(flowId, stepId, request);
    }

    @DeleteMapping("/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a step", description = "Delete a step by flow id")
    public void delete(@PathVariable Long flowId,
                       @PathVariable Long stepId) {
        flowStepService.delete(stepId);
    }
}
