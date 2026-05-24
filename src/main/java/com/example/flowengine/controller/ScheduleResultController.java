package com.example.flowengine.controller;

import com.example.flowengine.DTO.ModuleExecutionDetailDTO;
import com.example.flowengine.DTO.ModuleExecutionSummaryDTO;
import com.example.flowengine.service.ScheduleResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/schedule/modules")
@RequiredArgsConstructor
@Tag(name = "Schedule Results", description = "View scheduled execution history and results")
public class ScheduleResultController {

    private final ScheduleResultService scheduleResultService;

    /**
     * Paginated run history for a module.
     * GET /schedule/modules/{moduleId}/runs?page=0&size=20
     */
    @GetMapping("/{moduleId}/runs")
    @Operation(
            summary = "Get run history",
            description = "Returns a paginated list of all scheduled execution runs for a module, newest first."
    )
    public Page<ModuleExecutionSummaryDTO> getRunHistory(
            @PathVariable Long moduleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return scheduleResultService.getRunHistory(moduleId, page, size);
    }

    /**
     * Full detail for a single run — flows + steps + assertions.
     * GET /schedule/modules/runs/{executionId}
     */
    @GetMapping("/runs/{executionId}")
    @Operation(
            summary = "Get run detail",
            description = "Returns the full execution detail for a single module run including all flows, steps, assertion results and skip reasons."
    )
    public ModuleExecutionDetailDTO getRunDetail(@PathVariable Long executionId) {
        return scheduleResultService.getRunDetail(executionId);
    }

    /**
     * Most recent run summary — for the status card on the UI.
     * GET /schedule/modules/{moduleId}/runs/latest
     */
    @GetMapping("/{moduleId}/runs/latest")
    @Operation(
            summary = "Get latest run",
            description = "Returns the most recent execution summary for a module. Returns 204 if the module has never been executed."
    )
    public ResponseEntity<ModuleExecutionSummaryDTO> getLatestRun(@PathVariable Long moduleId) {
        return scheduleResultService.getLastRun(moduleId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}