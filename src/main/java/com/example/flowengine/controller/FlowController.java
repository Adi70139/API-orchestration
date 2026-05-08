
package com.example.flowengine.controller;

import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.service.FlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/flows")
@RequiredArgsConstructor
public class FlowController {

    private final FlowService flowService;

    @PostMapping
    public FlowDefinition create(@RequestBody FlowRequest flow) {
        return flowService.create(flow);
    }

    @GetMapping
    public List<FlowDefinition> getAll() {
        return flowService.getAll();
    }

    @GetMapping("/{id}")
    public FlowDefinition getById(@PathVariable Long id) {
        return flowService.getById(id);
    }

    @GetMapping("/module/{moduleName}")
    public List<FlowDefinition> getFlowsByModuleName(@PathVariable String moduleName) {
        return flowService.getFlowsByModuleName(moduleName);
    }
}

