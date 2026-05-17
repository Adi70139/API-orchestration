
package com.example.flowengine.controller;

import com.example.flowengine.DTO.DuplicateFlowRequest;
import com.example.flowengine.DTO.FlowDTO;
import com.example.flowengine.DTO.FlowDetailedDTO;
import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.service.FlowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/flows")
@RequiredArgsConstructor
@Tag(name = "Flow Creation", description = "Create and manage flows")
public class FlowController {

    private final FlowService flowService;

    @PostMapping
    @Operation(summary = "Create Flow", description = "Create a new flow. Optionally specify a default environment.")
    public FlowDTO create(@RequestBody FlowRequest flow) {
        return flowService.create(flow);
    }

    @GetMapping
    @Operation(summary = "Get all flows", description = "Get all flows.")
    public List<FlowDTO> getAll() {
        return flowService.getAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get Flow", description = "Get a flow by its ID, including its modules and default environment.")
    public FlowDetailedDTO getById(@PathVariable Long id) {
        return flowService.getById(id);
    }

    @GetMapping("/module/{moduleName}")
    @Operation(summary = "Get Flows by module", description = "Get all flows that contain a module with the specified name.")
    public List<FlowDTO> getFlowsByModuleName(@PathVariable String moduleName) {
        return flowService.getFlowsByModuleName(moduleName);
    }

    @DeleteMapping("/{flowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a Flow", description = "Delete a flow by its ID.")
    public void deleteFlow(@PathVariable Long flowId) {
        flowService.delete(flowId);
    }

    @PutMapping("/{flowId}/environment/{envId}")
    @Operation(summary = "Update a Flow", description = "Set or update the default environment for a flow.")
    public FlowDTO setEnvironment(@PathVariable Long flowId,
                                         @PathVariable Long envId) {
        return flowService.setDefaultEnvironment(flowId, envId);
    }

    @DeleteMapping("/{flowId}/environment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Clear Flow Environment", description = "Remove the default environment from a flow.")
    public void clearEnvironment(@PathVariable Long flowId) {
        flowService.clearDefaultEnvironment(flowId);
    }

    @PostMapping("/{flowId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Duplicate a Flow", description = "Duplicate an existing flow. Optionally specify a new name and whether to include modules.")
    public FlowDTO duplicate(@PathVariable Long flowId,
                                    @RequestBody(required = false) DuplicateFlowRequest request) {
        if (request == null) request = new DuplicateFlowRequest();
        return flowService.duplicateFlow(flowId, request);
    }

    @PutMapping("/{flowId}")
    @Operation(summary = "Update a Flow", description = "Update the basic details of a flow.")
    public FlowDTO setEnvironment(@PathVariable Long flowId,
                                            @RequestBody FlowRequest flowRequest) {
        return flowService.update(flowRequest, flowId);
    }
}

