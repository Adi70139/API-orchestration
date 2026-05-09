package com.example.flowengine.controller;

import com.example.flowengine.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * Download PDF report for latest flow execution.
     * GET /report/flows/{flowId}
     */
    @GetMapping("/flows/{flowId}")
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
    public ResponseEntity<byte[]> moduleReport(@PathVariable Long moduleExecutionId) throws IOException {
        byte[] pdf = reportService.generateModuleReport(moduleExecutionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"module-execution-" + moduleExecutionId + "-report.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}