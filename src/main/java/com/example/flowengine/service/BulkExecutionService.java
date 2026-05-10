package com.example.flowengine.service;

import com.example.flowengine.DTO.BulkJobItemResult;
import com.example.flowengine.DTO.BulkJobResult;
import com.example.flowengine.DTO.FlowExecutionResult;
import com.example.flowengine.DTO.ModuleExecutionResult;
import com.example.flowengine.constants.BulkJobStatus;
import com.example.flowengine.constants.ExecutionStatus;
import com.example.flowengine.entity.*;
import com.example.flowengine.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BulkExecutionService {

    private final ExecutorService executorService;
    private final BulkJobRepository bulkJobRepository;
    private final BulkJobItemRepository bulkJobItemRepository;
    private final ModuleRepository moduleRepository;
    private final FlowRepository flowRepository;

    public BulkJobResult startModuleBulkJob(List<Long> moduleIds) {
        BulkJob job = createJob("MODULE");

        List<BulkJobItem> items = new ArrayList<>();
        for (Long moduleId : moduleIds) {
            ModuleEntity module = moduleRepository.findById(moduleId)
                    .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleId));
            BulkJobItem item = new BulkJobItem();
            item.setBulkJob(job);
            item.setTargetId(moduleId);
            item.setTargetName(module.getName());
            item.setStatus(ExecutionStatus.IN_PROGRESS);
            items.add(bulkJobItemRepository.save(item));
        }

        // Run all modules in parallel
        List<CompletableFuture<Void>> futures = items.stream()
                .map(item -> CompletableFuture.runAsync(() -> runModuleItem(item)))
                .collect(Collectors.toList());

        // When all done, update bulk job status
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> finalizeBulkJob(job.getId()));

        return mapToResult(job, items);
    }

    public BulkJobResult startFlowBulkJob(List<Long> flowIds) {
        BulkJob job = createJob("FLOW");

        List<BulkJobItem> items = new ArrayList<>();
        for (Long flowId : flowIds) {
            FlowDefinition flow = flowRepository.findById(flowId)
                    .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
            BulkJobItem item = new BulkJobItem();
            item.setBulkJob(job);
            item.setTargetId(flowId);
            item.setTargetName(flow.getName());
            item.setStatus(ExecutionStatus.IN_PROGRESS);
            items.add(bulkJobItemRepository.save(item));
        }

        List<CompletableFuture<Void>> futures = items.stream()
                .map(item -> CompletableFuture.runAsync(() -> runFlowItem(item)))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> finalizeBulkJob(job.getId()));

        return mapToResult(job, items);
    }

    public BulkJobResult getJobStatus(Long bulkJobId) {
        BulkJob job = bulkJobRepository.findById(bulkJobId)
                .orElseThrow(() -> new IllegalArgumentException("Bulk job not found: " + bulkJobId));
        List<BulkJobItem> items = job.getItems();
        return mapToResult(job, items);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void runModuleItem(BulkJobItem item) {
        long start = System.currentTimeMillis();
        try {
            ModuleExecutionResult result = executorService.runModule(item.getTargetId());
            item.setStatus(result.isAllFlowsPassed() ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
            item.setExecutionId(result.getModuleExecutionId());
        } catch (Exception e) {
            log.error("Bulk module execution failed for module {}: {}", item.getTargetId(), e.getMessage());
            item.setStatus(ExecutionStatus.FAIL);
        } finally {
            item.setDurationMs(System.currentTimeMillis() - start);
            bulkJobItemRepository.save(item);
        }
    }

    private void runFlowItem(BulkJobItem item) {
        long start = System.currentTimeMillis();
        try {
            FlowExecutionResult result = executorService.runFlow(item.getTargetId());
            item.setStatus(result.isAllStepsPassed() ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
            item.setExecutionId(result.getFlowExecutionId());
        } catch (Exception e) {
            log.error("Bulk flow execution failed for flow {}: {}", item.getTargetId(), e.getMessage());
            item.setStatus(ExecutionStatus.FAIL);
        } finally {
            item.setDurationMs(System.currentTimeMillis() - start);
            bulkJobItemRepository.save(item);
        }
    }

    private void finalizeBulkJob(Long jobId) {
        BulkJob job = bulkJobRepository.findById(jobId).orElseThrow();
        List<BulkJobItem> items = job.getItems();
        boolean anyFailed = items.stream().anyMatch(i -> i.getStatus() == ExecutionStatus.FAIL);
        job.setStatus(anyFailed ? BulkJobStatus.PARTIAL_FAIL : BulkJobStatus.COMPLETED);
        job.setFinishedAt(LocalDateTime.now());
        bulkJobRepository.save(job);
    }

    private BulkJob createJob(String type) {
        BulkJob job = new BulkJob();
        job.setType(type);
        job.setStatus(BulkJobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        return bulkJobRepository.save(job);
    }

    private BulkJobResult mapToResult(BulkJob job, List<BulkJobItem> items) {
        BulkJobResult result = new BulkJobResult();
        result.setBulkJobId(job.getId());
        result.setType(job.getType());
        result.setStatus(job.getStatus());
        result.setStartedAt(job.getStartedAt());
        result.setFinishedAt(job.getFinishedAt());
        result.setItems(items.stream().map(item -> {
            BulkJobItemResult r = new BulkJobItemResult();
            r.setTargetId(item.getTargetId());
            r.setTargetName(item.getTargetName());
            r.setStatus(item.getStatus());
            r.setExecutionId(item.getExecutionId());
            r.setDurationMs(item.getDurationMs());
            return r;
        }).collect(Collectors.toList()));
        return result;
    }
}