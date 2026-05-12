package com.example.flowengine.controller;

import com.example.flowengine.DTO.FlowExecutionResult;
import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.DTO.ModuleExecutionResult;
import com.example.flowengine.service.ExecutorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/execute")
@RequiredArgsConstructor
@Tag(name = "Executor", description = "Execute modules and flows")
public class ExecutorController {

    private final ExecutorService executorService;

    /**
     * Run all flows in a module.
     * POST /execute/modules/{moduleId}?envId={envId}
     */
    @PostMapping("/modules/{moduleId}")
    @Operation(summary = "Run Module", description = "Execute a module.")
    public ModuleExecutionResult runModule(
            @PathVariable Long moduleId,
            @RequestParam(required = false) Long envId) {
        return executorService.runModule(moduleId, envId);
    }

    /**
     * Run a single flow.
     * POST /execute/flows/{flowId}
     */
    @PostMapping("/flows/{flowId}")
    @Operation(summary = "Run Flow", description = "Execute a Flow.")
    public FlowExecutionResult runFlow(
            @PathVariable Long flowId,
            @RequestBody(required = false) FlowRequest request) {
        Long envId = request != null ? request.getEnvironmentId() : null;
        return executorService.runFlow(flowId, envId);
    }
}
