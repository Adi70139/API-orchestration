package com.example.flowengine.controller;

import com.example.flowengine.DTO.DependencyGraphDTO;
import com.example.flowengine.DTO.FlowHistoryDTO;
import com.example.flowengine.DTO.StepTrendDTO;
import com.example.flowengine.service.TrendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
@Tag(name = "Trends & History", description = "Execution history, step trends, and dependency graphs")
public class TrendController {

    private final TrendService trendService;

    @GetMapping("/{flowId}/history")
    @Operation(
            summary = "Execution history for a flow",
            description = "Returns all past executions with per-step breakdown and trend summary."
    )
    public FlowHistoryDTO getFlowHistory(@PathVariable Long flowId) {
        return trendService.getFlowHistory(flowId);
    }

    @GetMapping("/{flowId}/trends")
    @Operation(
            summary = "Per-step trend data",
            description = "Returns pass rate, avg duration, flakiness indicator, and result timeline " +
                    "for each step in the flow over the last 20 runs."
    )
    public List<StepTrendDTO> getStepTrends(@PathVariable Long flowId) {
        return trendService.getStepTrends(flowId);
    }

    @GetMapping("/{flowId}/dependency-graph")
    @Operation(
            summary = "Step dependency graph",
            description = "Returns nodes and edges representing data dependencies between steps " +
                    "based on {placeholder} usage. Edges are labelled with the keys that flow between steps."
    )
    public DependencyGraphDTO getDependencyGraph(@PathVariable Long flowId) {
        return trendService.getDependencyGraph(flowId);
    }
}