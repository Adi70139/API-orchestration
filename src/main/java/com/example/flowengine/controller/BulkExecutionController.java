package com.example.flowengine.controller;

import com.example.flowengine.DTO.BulkExecutionRequest;
import com.example.flowengine.DTO.BulkJobResult;
import com.example.flowengine.service.BulkExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/execute")
@RequiredArgsConstructor
public class BulkExecutionController {

    private final BulkExecutionService bulkExecutionService;

    @PostMapping("/modules/bulk")
    public BulkJobResult runModulesBulk(@Valid @RequestBody BulkExecutionRequest request) {
        return bulkExecutionService.startModuleBulkJob(request.getIds(), request.getEnvIds());
    }

    @PostMapping("/flows/bulk")
    public BulkJobResult runFlowsBulk(@Valid @RequestBody BulkExecutionRequest request) {
        return bulkExecutionService.startFlowBulkJob(request.getIds(), request.getEnvIds());
    }

    @GetMapping("/bulk/{bulkJobId}")
    public BulkJobResult getBulkJobStatus(@PathVariable Long bulkJobId) {
        return bulkExecutionService.getJobStatus(bulkJobId);
    }
}