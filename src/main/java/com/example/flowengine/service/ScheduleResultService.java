package com.example.flowengine.service;

import com.example.flowengine.DTO.AssertionResult;
import com.example.flowengine.DTO.ModuleExecutionDetailDTO;
import com.example.flowengine.DTO.ModuleExecutionDetailDTO.FlowExecutionDetailDTO;
import com.example.flowengine.DTO.ModuleExecutionDetailDTO.StepExecutionDetailDTO;
import com.example.flowengine.DTO.ModuleExecutionSummaryDTO;
import com.example.flowengine.constants.ExecutionStatus;
import com.example.flowengine.entity.FlowExecution;
import com.example.flowengine.entity.ModuleExecution;
import com.example.flowengine.entity.StepExecution;
import com.example.flowengine.repository.ModuleExecutionRepository;
import com.example.flowengine.repository.ModuleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleResultService {

    private final ModuleExecutionRepository moduleExecutionRepository;
    private final ModuleRepository moduleRepository;
    private final ObjectMapper objectMapper;

    /**
     * Paginated list of execution runs for a module — newest first.
     */
    public Page<ModuleExecutionSummaryDTO> getRunHistory(Long moduleId, int page, int size) {
        moduleRepository.findById(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleId));

        return moduleExecutionRepository
                .findByModuleIdOrderByStartedAtDesc(moduleId, PageRequest.of(page, size))
                .map(this::toSummary);
    }

    /**
     * Full detail for a single module execution run.
     */
    public ModuleExecutionDetailDTO getRunDetail(Long moduleExecutionId) {
        ModuleExecution execution = moduleExecutionRepository.findById(moduleExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("Module execution not found: " + moduleExecutionId));
        return toDetail(execution);
    }

    /**
     * Most recent run summary for a module — useful for the UI status card.
     */
    public Optional<ModuleExecutionSummaryDTO> getLastRun(Long moduleId) {
        moduleRepository.findById(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleId));
        return moduleExecutionRepository.findLatestByModuleId(moduleId).map(this::toSummary);
    }

    // ─── Mappers ─────────────────────────────────────────────────────────────

    private ModuleExecutionSummaryDTO toSummary(ModuleExecution me) {
        ModuleExecutionSummaryDTO dto = new ModuleExecutionSummaryDTO();
        dto.setExecutionId(me.getId());
        dto.setModuleId(me.getModule().getId());
        dto.setModuleName(me.getModule().getName());
        dto.setStatus(me.getStatus());
        dto.setStartedAt(me.getStartedAt());
        dto.setFinishedAt(me.getFinishedAt());
        dto.setScheduledRun(true); // all runs through this service are schedule-originated

        if (me.getStartedAt() != null && me.getFinishedAt() != null) {
            dto.setDurationMs(Duration.between(me.getStartedAt(), me.getFinishedAt()).toMillis());
        }

        if (me.getFlowExecutions() != null) {
            int passed = 0, failed = 0, skipped = 0;
            for (FlowExecution fe : me.getFlowExecutions()) {
                if (fe.getStatus() == ExecutionStatus.PASS) passed++;
                else failed++;

                if (fe.getStepExecutions() != null) {
                    skipped += fe.getStepExecutions().stream()
                            .filter(se -> se.getStatus() == ExecutionStatus.SKIPPED)
                            .count();
                }
            }
            dto.setTotalFlows(me.getFlowExecutions().size());
            dto.setPassedFlows(passed);
            dto.setFailedFlows(failed);
            dto.setSkippedSteps(skipped);
        }

        return dto;
    }

    private ModuleExecutionDetailDTO toDetail(ModuleExecution me) {
        ModuleExecutionDetailDTO dto = new ModuleExecutionDetailDTO();
        dto.setExecutionId(me.getId());
        dto.setModuleId(me.getModule().getId());
        dto.setModuleName(me.getModule().getName());
        dto.setStatus(me.getStatus());
        dto.setStartedAt(me.getStartedAt());
        dto.setFinishedAt(me.getFinishedAt());

        if (me.getStartedAt() != null && me.getFinishedAt() != null) {
            dto.setDurationMs(Duration.between(me.getStartedAt(), me.getFinishedAt()).toMillis());
        }

        if (me.getFlowExecutions() != null) {
            dto.setFlows(me.getFlowExecutions().stream().map(this::toFlowDetail).toList());
        }

        return dto;
    }

    private FlowExecutionDetailDTO toFlowDetail(FlowExecution fe) {
        FlowExecutionDetailDTO dto = new FlowExecutionDetailDTO();
        dto.setFlowExecutionId(fe.getId());
        dto.setFlowId(fe.getFlow().getId());
        dto.setFlowName(fe.getFlow().getName());
        dto.setStatus(fe.getStatus());
        dto.setStartedAt(fe.getStartedAt());
        dto.setFinishedAt(fe.getFinishedAt());

        if (fe.getStartedAt() != null && fe.getFinishedAt() != null) {
            dto.setDurationMs(Duration.between(fe.getStartedAt(), fe.getFinishedAt()).toMillis());
        }

        if (fe.getStepExecutions() != null) {
            dto.setSteps(fe.getStepExecutions().stream().map(this::toStepDetail).toList());
        }

        return dto;
    }

    private StepExecutionDetailDTO toStepDetail(StepExecution se) {
        StepExecutionDetailDTO dto = new StepExecutionDetailDTO();
        dto.setStepExecutionId(se.getId());
        dto.setStepId(se.getStepId());
        dto.setStepName(se.getStepName());
        dto.setStepOrder(se.getStepOrder());
        dto.setStatus(se.getStatus());
        dto.setStartedAt(se.getStartedAt());
        dto.setFinishedAt(se.getFinishedAt());
        dto.setDurationMs(se.getDurationMs());
        dto.setResolvedUrl(se.getResolvedUrl());
        dto.setStatusCode(se.getStatusCode());
        dto.setResponseBody(se.getResponseBody());
        dto.setTotalAttempts(se.getTotalAttempts());

        boolean skipped = se.getStatus() == ExecutionStatus.SKIPPED;
        dto.setSkipped(skipped);

        // Extract skip reason from errorMessage ("SKIPPED: <reason>")
        if (skipped && se.getErrorMessage() != null && se.getErrorMessage().startsWith("SKIPPED: ")) {
            dto.setSkipReason(se.getErrorMessage().substring("SKIPPED: ".length()));
        } else {
            dto.setErrorMessage(se.getErrorMessage());
        }

        // Deserialize assertion results
        if (se.getAssertionResultsJson() != null) {
            try {
                List<AssertionResult> assertions = objectMapper.readValue(
                        se.getAssertionResultsJson(),
                        new TypeReference<>() {});
                dto.setAssertionResults(assertions);
            } catch (Exception e) {
                log.warn("Could not deserialize assertion results for stepExecution {}: {}", se.getId(), e.getMessage());
            }
        }

        return dto;
    }
}