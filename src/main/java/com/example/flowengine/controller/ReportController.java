package com.example.flowengine.controller;

import com.example.flowengine.DTO.BulkReportDTO;
import com.example.flowengine.DTO.FlowReportDTO;
import com.example.flowengine.DTO.ModuleReportDTO;
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

    /**
     * Get JSON data for flow execution report (for UI display).
     * GET /report/flows/{flowId}/data
     */
    @GetMapping("/flows/{flowId}/data")
    @Operation(summary = "Get flow report data", description = "Get JSON data for the latest execution of a flow for UI display.")
    public FlowReportDTO getFlowReportData(@PathVariable Long flowId) {
        return reportService.getFlowReportData(flowId);
    }

    /**
     * Get JSON data for module execution report (for UI display).
     * GET /report/module-executions/{moduleExecutionId}/data
     */
    @GetMapping("/module-executions/{moduleExecutionId}/data")
    @Operation(summary = "Get module report data", description = "Get JSON data for a module execution for UI display.")
    public ModuleReportDTO getModuleReportData(@PathVariable Long moduleExecutionId) {
        return reportService.getModuleReportData(moduleExecutionId);
    }

    /**
     * Download PDF report for a scheduled module execution run.
     * GET /report/schedule/runs/{moduleExecutionId}
     * Identical data to module-executions/{id} but surfaced under /report/schedule
     * so the UI schedule history page can link to it directly.
     */
    @GetMapping("/schedule/runs/{moduleExecutionId}")
    @Operation(summary = "Download schedule run report", description = "Generate a PDF report for a scheduled module execution run. Includes all flows, steps, skip reasons, assertions, retries and poll attempts.")
    public ResponseEntity<byte[]> scheduleRunReport(@PathVariable Long moduleExecutionId) throws IOException {
        byte[] pdf = reportService.generateModuleReport(moduleExecutionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"schedule-run-" + moduleExecutionId + "-report.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * Get JSON data for a scheduled run (for UI display without download).
     * GET /report/schedule/runs/{moduleExecutionId}/data
     */
    @GetMapping("/schedule/runs/{moduleExecutionId}/data")
    @Operation(summary = "Get schedule run report data", description = "Get JSON report data for a scheduled module execution run.")
    public ModuleReportDTO getScheduleRunReportData(@PathVariable Long moduleExecutionId) {
        return reportService.getModuleReportData(moduleExecutionId);
    }
    @GetMapping("/bulk/{bulkJobId}/data")
    @Operation(summary = "Get bulk job report data", description = "Get JSON data for a bulk execution job for UI display.")
    public BulkReportDTO getBulkReportData(@PathVariable Long bulkJobId) {
        return reportService.getBulkReportData(bulkJobId);
    }
}