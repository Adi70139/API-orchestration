package com.example.flowengine.controller;

import com.example.flowengine.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/report")
@RequiredArgsConstructor
@Tag(name = "Report generation", description = "Generate PDF reports for flows, module executions, and bulk jobs")
public class ReportController {

    private final ReportService reportService;

    /**
     * Download PDF report for latest flow execution.
     * GET /report/flows/{flowId}
     */
    @GetMapping("/flows/{flowId}")
    @Operation(summary = "Create flow report", description = "Generate a PDF report for the latest execution of a flow.")
    public ResponseEntity<byte[]> flowReport(@PathVariable Long flowId) throws IOException {
        byte[] pdf = reportService.generateFlowReport(flowId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"flow-" + flowId + "-report.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * Download PDF report for a module execution run.
     * GET /report/module-executions/{moduleExecutionId}
     */
    @GetMapping("/module-executions/{moduleExecutionId}")
    @Operation(summary = "Create module report", description = "Generate a PDF report for the latest execution of a module.")
    public ResponseEntity<byte[]> moduleReport(@PathVariable Long moduleExecutionId) throws IOException {
        byte[] pdf = reportService.generateModuleReport(moduleExecutionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"module-execution-" + moduleExecutionId + "-report.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * Download PDF report for a bulk execution job.
     * GET /report/bulk/{bulkJobId}
     */
    @GetMapping("/bulk/{bulkJobId}")
    @Operation(summary = "Create bulk job report", description = "Generate a PDF report for a bulk execution job.")
    public ResponseEntity<byte[]> bulkReport(@PathVariable Long bulkJobId) throws IOException {
        byte[] pdf = reportService.generateBulkReport(bulkJobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bulk-job-" + bulkJobId + "-report.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}