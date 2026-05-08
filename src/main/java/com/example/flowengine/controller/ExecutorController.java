package com.example.flowengine.controller;

import com.example.flowengine.DTO.FlowExecutionResult;
import com.example.flowengine.DTO.ModuleExecutionResult;
import com.example.flowengine.service.ExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/execute")
@RequiredArgsConstructor
public class ExecutorController {

    private final ExecutorService executorService;

    /**
     * Run all flows in a module.
     * POST /execute/modules/{moduleId}
     */
    @PostMapping("/modules/{moduleId}")
    public ModuleExecutionResult runModule(@PathVariable Long moduleId) {
        return executorService.runModule(moduleId);
    }

    /**
     * Run a single flow.
     * POST /execute/flows/{flowId}
     */
    @PostMapping("/flows/{flowId}")
    public FlowExecutionResult runFlow(@PathVariable Long flowId) {
        return executorService.runFlow(flowId);
    }
}
