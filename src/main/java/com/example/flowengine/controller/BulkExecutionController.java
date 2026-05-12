package com.example.flowengine.controller;

import com.example.flowengine.DTO.BulkExecutionRequest;
import com.example.flowengine.DTO.BulkJobResult;
import com.example.flowengine.service.BulkExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/execute")
@RequiredArgsConstructor
@Tag(name = "Bulk executor", description = "Execute modules and flows in bulk")
public class BulkExecutionController {

    private final BulkExecutionService bulkExecutionService;

    @PostMapping("/modules/bulk")
    @Operation(summary = "Run Modules", description = "Execute multiple modules in bulk. Provide a list of module IDs and environment IDs.")
    public BulkJobResult runModulesBulk(@Valid @RequestBody BulkExecutionRequest request) {
        return bulkExecutionService.startModuleBulkJob(request.getIds(), request.getEnvIds());
    }

    @PostMapping("/flows/bulk")
    @Operation(summary = "Run Flows", description = "Execute multiple flows in bulk. Provide a list of flow IDs and environment IDs.")
    public BulkJobResult runFlowsBulk(@Valid @RequestBody BulkExecutionRequest request) {
        return bulkExecutionService.startFlowBulkJob(request.getIds(), request.getEnvIds());
    }

    @GetMapping("/bulk/{bulkJobId}")
    @Operation(summary = "Get Bulk id", description = "Get the status and results of a bulk execution job by its ID.")
    public BulkJobResult getBulkJobStatus(@PathVariable Long bulkJobId) {
        return bulkExecutionService.getJobStatus(bulkJobId);
    }
}