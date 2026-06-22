package com.example.flowengine.controller;

import com.example.flowengine.DTO.FlowExecutionStartResponse;
import com.example.flowengine.DTO.FlowExecutionResult;
import com.example.flowengine.DTO.FlowExecutionStatusDTO;
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
     * POST /execute/modules/{moduleId}?envId={envId}&parallel=true
     *
     * parallel defaults to false — flows in a module often share state (login session, test
     * data, rate-limited environment), so running them concurrently can turn deterministic
     * failures into flaky ones. Only pass parallel=true for modules you've verified are made of
     * independent flows.
     */
    @PostMapping("/modules/{moduleId}")
    @Operation(summary = "Run Module", description = "Execute a module. Set parallel=true to run its " +
            "flows concurrently instead of sequentially — only safe for modules with independent flows.")
    public ModuleExecutionResult runModule(
            @PathVariable Long moduleId,
            @RequestParam(required = false) Long envId,
            @RequestParam(required = false, defaultValue = "false") boolean parallel) {
        return executorService.runModule(moduleId, envId, parallel);
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

    /**
     * Start a single flow in the background.
     * POST /execute/flows/{flowId}/async
     */
    @PostMapping("/flows/{flowId}/async")
    @Operation(summary = "Start Flow Async", description = "Start a flow execution and poll its status separately.")
    public FlowExecutionStartResponse startFlowAsync(
            @PathVariable Long flowId,
            @RequestBody(required = false) FlowRequest request) {
        Long envId = request != null ? request.getEnvironmentId() : null;
        return executorService.startFlowAsync(flowId, envId);
    }

    /**
     * Poll live status for a flow execution.
     * GET /execute/flows/runs/{flowExecutionId}/status
     */
    @GetMapping("/flows/runs/{flowExecutionId}/status")
    @Operation(summary = "Get Flow Execution Status", description = "Get live per-step status for a flow execution.")
    public FlowExecutionStatusDTO getFlowExecutionStatus(@PathVariable Long flowExecutionId) {
        return executorService.getFlowExecutionStatus(flowExecutionId);
    }
}