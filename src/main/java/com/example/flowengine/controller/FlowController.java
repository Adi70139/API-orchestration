
package com.example.flowengine.controller;

import com.example.flowengine.DTO.DuplicateFlowRequest;
import com.example.flowengine.DTO.FlowDTO;
import com.example.flowengine.DTO.FlowDetailedDTO;
import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.service.FlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/flows")
@RequiredArgsConstructor
public class FlowController {

    private final FlowService flowService;

    @PostMapping
    public FlowDTO create(@RequestBody FlowRequest flow) {
        return flowService.create(flow);
    }

    @GetMapping
    public List<FlowDTO> getAll() {
        return flowService.getAll();
    }

    @GetMapping("/{id}")
    public FlowDetailedDTO getById(@PathVariable Long id) {
        return flowService.getById(id);
    }

    @GetMapping("/module/{moduleName}")
    public List<FlowDTO> getFlowsByModuleName(@PathVariable String moduleName) {
        return flowService.getFlowsByModuleName(moduleName);
    }

    @DeleteMapping("/{flowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFlow(@PathVariable Long flowId) {
        flowService.delete(flowId);
    }

    @PutMapping("/{flowId}/environment/{envId}")
    public FlowDTO setEnvironment(@PathVariable Long flowId,
                                         @PathVariable Long envId) {
        return flowService.setDefaultEnvironment(flowId, envId);
    }

    @DeleteMapping("/{flowId}/environment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearEnvironment(@PathVariable Long flowId) {
        flowService.clearDefaultEnvironment(flowId);
    }

    @PostMapping("/{flowId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public FlowDTO duplicate(@PathVariable Long flowId,
                                    @RequestBody(required = false) DuplicateFlowRequest request) {
        if (request == null) request = new DuplicateFlowRequest();
        return flowService.duplicateFlow(flowId, request);
    }
}

