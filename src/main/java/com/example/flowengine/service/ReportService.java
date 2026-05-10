package com.example.flowengine.service;

import com.example.flowengine.constants.ExecutionStatus;
import com.example.flowengine.entity.*;
import com.example.flowengine.repository.*;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");
    private static final DeviceRgb COLOR_PASS = new DeviceRgb(34, 197, 94);
    private static final DeviceRgb COLOR_FAIL = new DeviceRgb(239, 68, 68);
    private static final DeviceRgb COLOR_HEADER_BG = new DeviceRgb(30, 41, 59);
    private static final DeviceRgb COLOR_ROW_ALT = new DeviceRgb(241, 245, 249);
    private static final DeviceRgb COLOR_SECTION_BG = new DeviceRgb(248, 250, 252);
    private static final DeviceRgb COLOR_BORDER = new DeviceRgb(203, 213, 225);

    private final FlowExecutionRepository flowExecutionRepository;
    private final ModuleExecutionRepository moduleExecutionRepository;
    private final FlowRepository flowRepository;
    private final BulkJobRepository bulkJobRepository;
    private final StepExecutionRepository stepExecutionRepository;

    public byte[] generateFlowReport(Long flowId) throws IOException {
        FlowExecution execution = flowExecutionRepository.findByFlowId(flowId)
                .orElseThrow(() -> new IllegalArgumentException("No execution found for flow id: " + flowId));

        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdf = new PdfDocument(writer);
        Document doc = new Document(pdf);
        doc.setMargins(40, 40, 40, 40);

        PdfFont bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
        PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        PdfFont mono = PdfFontFactory.createFont(StandardFonts.COURIER);

        addHeader(doc, bold, regular, "Flow Execution Report");
        addFlowSummary(doc, bold, regular, flow, execution);
        addStepDetails(doc, bold, regular, mono, execution.getStepExecutions());

        doc.close();
        return baos.toByteArray();
    }

    public byte[] generateModuleReport(Long moduleExecutionId) throws IOException {
        ModuleExecution moduleExecution = moduleExecutionRepository.findById(moduleExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("Module execution not found: " + moduleExecutionId));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdf = new PdfDocument(writer);
        Document doc = new Document(pdf);
        doc.setMargins(40, 40, 40, 40);

        PdfFont bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
        PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        PdfFont mono = PdfFontFactory.createFont(StandardFonts.COURIER);

        addHeader(doc, bold, regular, "Module Execution Report");
        addModuleSummary(doc, bold, regular, moduleExecution);

        List<FlowExecution> flowExecutions = moduleExecution.getFlowExecutions();
        if (flowExecutions != null) {
            for (FlowExecution flowExecution : flowExecutions) {
                doc.add(new AreaBreak());
                addFlowSection(doc, bold, regular, mono, flowExecution);
            }
        }

        doc.close();
        return baos.toByteArray();
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private void addHeader(Document doc, PdfFont bold, PdfFont regular, String title) throws IOException {
        // Title bar
        Table titleBar = new Table(UnitValue.createPercentArray(new float[]{1}))
                .useAllAvailableWidth();
        Cell titleCell = new Cell()
                .add(new Paragraph(title).setFont(bold).setFontSize(20).setFontColor(ColorConstants.WHITE))
                .add(new Paragraph("API Flow Engine").setFont(regular).setFontSize(10).setFontColor(ColorConstants.WHITE))
                .setBackgroundColor(COLOR_HEADER_BG)
                .setPadding(20)
                .setBorder(Border.NO_BORDER);
        titleBar.addCell(titleCell);
        doc.add(titleBar);
        doc.add(new Paragraph("\n").setFontSize(4));
    }

    // ── Module Summary ────────────────────────────────────────────────────────

    private void addModuleSummary(Document doc, PdfFont bold, PdfFont regular, ModuleExecution me) {
        boolean passed = me.getStatus() == ExecutionStatus.PASS;
        int totalFlows = me.getFlowExecutions() != null ? me.getFlowExecutions().size() : 0;
        long passedFlows = me.getFlowExecutions() != null
                ? me.getFlowExecutions().stream().filter(f -> f.getStatus() == ExecutionStatus.PASS).count() : 0;

        addSectionTitle(doc, bold, "Module Summary");

        Table summary = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
        addSummaryRow(summary, regular, bold, "Module", me.getModule().getName(), false);
        addSummaryRow(summary, regular, bold, "Status", passed ? "PASSED" : "FAILED", true);
        addSummaryRow(summary, regular, bold, "Started At", me.getStartedAt() != null ? me.getStartedAt().format(FORMATTER) : "-", false);
        addSummaryRow(summary, regular, bold, "Finished At", me.getFinishedAt() != null ? me.getFinishedAt().format(FORMATTER) : "-", true);
        addSummaryRow(summary, regular, bold, "Total Flows", totalFlows + " (" + passedFlows + " passed, " + (totalFlows - passedFlows) + " failed)", false);
        doc.add(summary);
        doc.add(new Paragraph("\n").setFontSize(6));

        // Flow overview table
        if (me.getFlowExecutions() != null && !me.getFlowExecutions().isEmpty()) {
            addSectionTitle(doc, bold, "Flow Overview");
            Table overview = new Table(UnitValue.createPercentArray(new float[]{3, 2, 2, 2})).useAllAvailableWidth();
            addTableHeader(overview, bold, "Flow Name", "Status", "Started At", "Duration");
            boolean alt = false;
            for (FlowExecution fe : me.getFlowExecutions()) {
                long duration = 0;
                if (fe.getStartedAt() != null && fe.getFinishedAt() != null) {
                    duration = java.time.Duration.between(fe.getStartedAt(), fe.getFinishedAt()).toMillis();
                }
                boolean fp = fe.getStatus() == ExecutionStatus.PASS;
                DeviceRgb bg = alt ? COLOR_ROW_ALT : null;
                addTableRow(overview, regular, bg,
                        fe.getFlow().getName(),
                        fp ? "PASSED" : "FAILED",
                        fe.getStartedAt() != null ? fe.getStartedAt().format(FORMATTER) : "-",
                        duration + " ms"
                );
                alt = !alt;
            }
            doc.add(overview);
        }
    }

    // ── Flow Summary ──────────────────────────────────────────────────────────

    private void addFlowSummary(Document doc, PdfFont bold, PdfFont regular,
                                FlowDefinition flow, FlowExecution execution) {
        boolean passed = execution.getStatus() == ExecutionStatus.PASS;
        int totalSteps = execution.getStepExecutions() != null ? execution.getStepExecutions().size() : 0;
        long passedSteps = execution.getStepExecutions() != null
                ? execution.getStepExecutions().stream().filter(StepExecution::isSuccess).count() : 0;
        long duration = 0;
        if (execution.getStartedAt() != null && execution.getFinishedAt() != null) {
            duration = java.time.Duration.between(execution.getStartedAt(), execution.getFinishedAt()).toMillis();
        }

        addSectionTitle(doc, bold, "Flow Summary");

        Table summary = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
        addSummaryRow(summary, regular, bold, "Flow Name", flow.getName(), false);
        addSummaryRow(summary, regular, bold, "Description", flow.getDescription() != null ? flow.getDescription() : "-", true);
        addSummaryRow(summary, regular, bold, "Status", passed ? "PASSED" : "FAILED", false);
        addSummaryRow(summary, regular, bold, "Started At", execution.getStartedAt() != null ? execution.getStartedAt().format(FORMATTER) : "-", true);
        addSummaryRow(summary, regular, bold, "Finished At", execution.getFinishedAt() != null ? execution.getFinishedAt().format(FORMATTER) : "-", false);
        addSummaryRow(summary, regular, bold, "Total Duration", duration + " ms", true);
        addSummaryRow(summary, regular, bold, "Steps", totalSteps + " total, " + passedSteps + " passed, " + (totalSteps - passedSteps) + " failed", false);
        doc.add(summary);
        doc.add(new Paragraph("\n").setFontSize(6));
    }

    private void addFlowSection(Document doc, PdfFont bold, PdfFont regular, PdfFont mono, FlowExecution fe) {
        boolean passed = fe.getStatus() == ExecutionStatus.PASS;
        long duration = 0;
        if (fe.getStartedAt() != null && fe.getFinishedAt() != null) {
            duration = java.time.Duration.between(fe.getStartedAt(), fe.getFinishedAt()).toMillis();
        }

        addSectionTitle(doc, bold, "Flow: " + fe.getFlow().getName());

        Table summary = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
        addSummaryRow(summary, regular, bold, "Status", passed ? "PASSED" : "FAILED", false);
        addSummaryRow(summary, regular, bold, "Started At", fe.getStartedAt() != null ? fe.getStartedAt().format(FORMATTER) : "-", true);
        addSummaryRow(summary, regular, bold, "Finished At", fe.getFinishedAt() != null ? fe.getFinishedAt().format(FORMATTER) : "-", false);
        addSummaryRow(summary, regular, bold, "Total Duration", duration + " ms", true);
        doc.add(summary);
        doc.add(new Paragraph("\n").setFontSize(6));

        addStepDetails(doc, bold, regular, mono, fe.getStepExecutions());
    }

    // ── Step Details ──────────────────────────────────────────────────────────

    private void addStepDetails(Document doc, PdfFont bold, PdfFont regular, PdfFont mono,
                                List<StepExecution> steps) {
        if (steps == null || steps.isEmpty()) return;

        addSectionTitle(doc, bold, "Step Details");

        for (StepExecution step : steps) {
            boolean success = step.isSuccess();
            DeviceRgb statusColor = success ? COLOR_PASS : COLOR_FAIL;

            // Step card
            Table card = new Table(UnitValue.createPercentArray(new float[]{1})).useAllAvailableWidth();
            card.setMarginBottom(12);

            // Step header row
            Table headerRow = new Table(UnitValue.createPercentArray(new float[]{6, 2})).useAllAvailableWidth();
            Cell nameCell = new Cell()
                    .add(new Paragraph("Step " + step.getStepOrder() + ": " + step.getStepName())
                            .setFont(bold).setFontSize(11).setFontColor(COLOR_HEADER_BG))
                    .setBorder(Border.NO_BORDER)
                    .setPaddingBottom(4);
            Cell statusCell = new Cell()
                    .add(new Paragraph(success ? "PASSED" : "FAILED")
                            .setFont(bold).setFontSize(10).setFontColor(ColorConstants.WHITE)
                            .setTextAlignment(TextAlignment.CENTER))
                    .setBackgroundColor(statusColor)
                    .setBorder(Border.NO_BORDER)
                    .setPadding(4)
                    .setTextAlignment(TextAlignment.CENTER);
            headerRow.addCell(nameCell);
            headerRow.addCell(statusCell);

            Cell cardCell = new Cell()
                    .add(headerRow)
                    .setBorder(new SolidBorder(COLOR_BORDER, 1))
                    .setBackgroundColor(COLOR_SECTION_BG)
                    .setPadding(12);

            // Details table inside card
            Table details = new Table(UnitValue.createPercentArray(new float[]{2, 5})).useAllAvailableWidth();
            details.setMarginTop(8);
            addDetailRow(details, bold, regular, "URL", step.getResolvedUrl(), false);
            addDetailRow(details, bold, regular, "Status Code", step.getStatusCode() != null ? String.valueOf(step.getStatusCode()) : "-", true);
            addDetailRow(details, bold, regular, "Duration", step.getDurationMs() != null ? step.getDurationMs() + " ms" : "-", false);

            if (step.getResolvedHeadersJson() != null) {
                addDetailRow(details, bold, mono, "Headers Sent", step.getResolvedHeadersJson(), true);
            }
            if (step.getResolvedBodyJson() != null) {
                addDetailRow(details, bold, mono, "Request Body", step.getResolvedBodyJson(), false);
            }
            if (step.getResponseBody() != null) {
                String responsePreview = step.getResponseBody().length() > 500
                        ? step.getResponseBody().substring(0, 500) + "\n... [truncated]"
                        : step.getResponseBody();
                addDetailRow(details, bold, mono, "Response Body", responsePreview, true);
            }
            if (!success && step.getErrorMessage() != null) {
                addDetailRow(details, bold, regular, "Error", step.getErrorMessage(), false);
            }

            cardCell.add(details);
            card.addCell(cardCell);
            doc.add(card);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addSectionTitle(Document doc, PdfFont bold, String title) {
        doc.add(new Paragraph(title)
                .setFont(bold)
                .setFontSize(13)
                .setFontColor(COLOR_HEADER_BG)
                .setMarginTop(10)
                .setMarginBottom(6));
    }

    private void addSummaryRow(Table table, PdfFont regular, PdfFont bold,
                               String label, String value, boolean alt) {
        DeviceRgb bg = alt ? COLOR_ROW_ALT : null;
        Cell labelCell = new Cell()
                .add(new Paragraph(label).setFont(bold).setFontSize(10))
                .setBorder(Border.NO_BORDER)
                .setPadding(6);
        Cell valueCell = new Cell()
                .add(new Paragraph(value).setFont(regular).setFontSize(10))
                .setBorder(Border.NO_BORDER)
                .setPadding(6);
        if (bg != null) {
            labelCell.setBackgroundColor(bg);
            valueCell.setBackgroundColor(bg);
        }
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    private void addTableHeader(Table table, PdfFont bold, String... headers) {
        for (String header : headers) {
            table.addCell(new Cell()
                    .add(new Paragraph(header).setFont(bold).setFontSize(10).setFontColor(ColorConstants.WHITE))
                    .setBackgroundColor(COLOR_HEADER_BG)
                    .setPadding(6)
                    .setBorder(Border.NO_BORDER));
        }
    }

    private void addTableRow(Table table, PdfFont regular, DeviceRgb bg, String... values) {
        for (String value : values) {
            Cell cell = new Cell()
                    .add(new Paragraph(value != null ? value : "-").setFont(regular).setFontSize(9))
                    .setPadding(5)
                    .setBorder(new SolidBorder(COLOR_BORDER, 0.5f));
            if (bg != null) cell.setBackgroundColor(bg);
            table.addCell(cell);
        }
    }

    private void addDetailRow(Table table, PdfFont labelFont, PdfFont valueFont,
                              String label, String value, boolean alt) {
        DeviceRgb bg = alt ? COLOR_ROW_ALT : null;
        Cell labelCell = new Cell()
                .add(new Paragraph(label).setFont(labelFont).setFontSize(9))
                .setPadding(5)
                .setBorder(new SolidBorder(COLOR_BORDER, 0.5f));
        Cell valueCell = new Cell()
                .add(new Paragraph(value != null ? value : "-").setFont(valueFont).setFontSize(9))
                .setPadding(5)
                .setBorder(new SolidBorder(COLOR_BORDER, 0.5f));
        if (bg != null) {
            labelCell.setBackgroundColor(bg);
            valueCell.setBackgroundColor(bg);
        }
        table.addCell(labelCell);
        table.addCell(valueCell);
    }

    public byte[] generateBulkReport(Long bulkJobId) throws IOException {
        BulkJob bulkJob = bulkJobRepository.findById(bulkJobId)
                .orElseThrow(() -> new IllegalArgumentException("Bulk job not found: " + bulkJobId));

        List<BulkJobItem> items = bulkJob.getItems();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdf = new PdfDocument(writer);
        Document doc = new Document(pdf);
        doc.setMargins(40, 40, 40, 40);

        PdfFont bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
        PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        PdfFont mono = PdfFontFactory.createFont(StandardFonts.COURIER);

        // ── Cover page ────────────────────────────────────────────────────────────
        addHeader(doc, bold, regular, "Bulk Execution Report");

        // Bulk job summary
        long totalDuration = 0;
        if (bulkJob.getStartedAt() != null && bulkJob.getFinishedAt() != null) {
            totalDuration = java.time.Duration.between(bulkJob.getStartedAt(), bulkJob.getFinishedAt()).toMillis();
        }
        long passedCount = items.stream().filter(i -> i.getStatus() == ExecutionStatus.PASS).count();
        long failedCount = items.stream().filter(i -> i.getStatus() == ExecutionStatus.FAIL).count();
        long runningCount = items.stream().filter(i -> i.getStatus() == ExecutionStatus.IN_PROGRESS).count();

        addSectionTitle(doc, bold, "Bulk Job Summary");
        Table summary = new Table(UnitValue.createPercentArray(new float[]{1, 1})).useAllAvailableWidth();
        addSummaryRow(summary, regular, bold, "Bulk Job ID", String.valueOf(bulkJob.getId()), false);
        addSummaryRow(summary, regular, bold, "Type", bulkJob.getType(), true);
        addSummaryRow(summary, regular, bold, "Status", bulkJob.getStatus().name(), false);
        addSummaryRow(summary, regular, bold, "Started At", bulkJob.getStartedAt() != null ? bulkJob.getStartedAt().format(FORMATTER) : "-", true);
        addSummaryRow(summary, regular, bold, "Finished At", bulkJob.getFinishedAt() != null ? bulkJob.getFinishedAt().format(FORMATTER) : "-", false);
        addSummaryRow(summary, regular, bold, "Total Duration", totalDuration + " ms", true);
        addSummaryRow(summary, regular, bold, "Total Items", items.size() + " (" + passedCount + " passed, " + failedCount + " failed" + (runningCount > 0 ? ", " + runningCount + " still running" : "") + ")", false);
        doc.add(summary);
        doc.add(new Paragraph("\n").setFontSize(6));

        // ── Items overview table ──────────────────────────────────────────────────
        addSectionTitle(doc, bold, "Items Overview");
        Table overview = new Table(UnitValue.createPercentArray(new float[]{3, 2, 2, 2})).useAllAvailableWidth();
        addTableHeader(overview, bold, "Name", "Status", "Duration", "Execution ID");
        boolean alt = false;
        for (BulkJobItem item : items) {
            DeviceRgb bg = alt ? COLOR_ROW_ALT : null;
            addTableRow(overview, regular, bg,
                    item.getTargetName(),
                    item.getStatus().name(),
                    item.getDurationMs() != null ? item.getDurationMs() + " ms" : "-",
                    item.getExecutionId() != null ? String.valueOf(item.getExecutionId()) : "-"
            );
            alt = !alt;
        }
        doc.add(overview);

        // ── Per item detail pages ─────────────────────────────────────────────────
        for (BulkJobItem item : items) {
            doc.add(new AreaBreak());

            if ("MODULE".equals(bulkJob.getType()) && item.getExecutionId() != null) {
                ModuleExecution moduleExecution = moduleExecutionRepository.findById(item.getExecutionId())
                        .orElse(null);
                if (moduleExecution != null) {
                    addModuleSummary(doc, bold, regular, moduleExecution);
                    List<FlowExecution> flowExecutions = moduleExecution.getFlowExecutions();
                    if (flowExecutions != null) {
                        for (FlowExecution fe : flowExecutions) {
                            doc.add(new AreaBreak());
                            addFlowSection(doc, bold, regular, mono, fe);
                        }
                    }
                } else {
                    doc.add(new Paragraph("No execution data available for: " + item.getTargetName())
                            .setFont(regular).setFontSize(10));
                }

            } else if ("FLOW".equals(bulkJob.getType()) && item.getExecutionId() != null) {
                FlowExecution fe = flowExecutionRepository.findById(item.getExecutionId())
                        .orElse(null);
                if (fe != null) {
                    addSectionTitle(doc, bold, "Flow: " + item.getTargetName());
                    addFlowSection(doc, bold, regular, mono, fe);
                } else {
                    doc.add(new Paragraph("No execution data available for: " + item.getTargetName())
                            .setFont(regular).setFontSize(10));
                }

            } else {
                // Still running or failed before execution was created
                addSectionTitle(doc, bold, item.getTargetName());
                doc.add(new Paragraph("Status: " + item.getStatus().name() + " — no execution detail available.")
                        .setFont(regular).setFontSize(10).setFontColor(COLOR_FAIL));
            }
        }

        doc.close();
        return baos.toByteArray();
    }
}