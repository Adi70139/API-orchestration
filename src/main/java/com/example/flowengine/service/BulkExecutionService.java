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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    public BulkJobResult startModuleBulkJob(List<Long> moduleIds, List<Long> envIds) {
        BulkJob job = createJob("MODULE");

        List<BulkJobItem> items = new ArrayList<>();
        for (int i = 0; i < moduleIds.size(); i++) {
            Long moduleId = moduleIds.get(i);
            // Safely resolve envId — null if not provided or explicitly null at this index
            Long envId = resolveEnvId(envIds, i);

            ModuleEntity module = moduleRepository.findById(moduleId)
                    .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleId));

            BulkJobItem item = new BulkJobItem();
            item.setBulkJob(job);
            item.setTargetId(moduleId);
            item.setTargetName(module.getName());
            item.setStatus(ExecutionStatus.IN_PROGRESS);
            item.setEnvironmentId(envId);
            items.add(bulkJobItemRepository.save(item));
        }

        List<CompletableFuture<Void>> futures = items.stream()
                .map(item -> CompletableFuture.runAsync(() -> runModuleItem(item)))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> finalizeBulkJob(job.getId()));

        return mapToResult(job, items);
    }

    public BulkJobResult startFlowBulkJob(List<Long> flowIds, List<Long> envIds) {
        BulkJob job = createJob("FLOW");

        List<BulkJobItem> items = new ArrayList<>();
        for (int i = 0; i < flowIds.size(); i++) {
            Long flowId = flowIds.get(i);
            Long envId = resolveEnvId(envIds, i);

            FlowDefinition flow = flowRepository.findById(flowId)
                    .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

            BulkJobItem item = new BulkJobItem();
            item.setBulkJob(job);
            item.setTargetId(flowId);
            item.setTargetName(flow.getName());
            item.setStatus(ExecutionStatus.IN_PROGRESS);
            item.setEnvironmentId(envId);
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
        return mapToResult(job, job.getItems());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Safely resolve env ID by index.
     * Returns null if envIds is null, empty, or the value at index is null.
     */
    private Long resolveEnvId(List<Long> envIds, int index) {
        if (envIds == null || envIds.isEmpty()) return null;
        if (index >= envIds.size()) return null;
        return envIds.get(index); // can be null — that's fine
    }

    private void runModuleItem(BulkJobItem item) {
        long start = System.currentTimeMillis();
        try {
            // Pass null env if not set — ExecutorService handles fallback gracefully
            ModuleExecutionResult result = executorService.runModule(
                    item.getTargetId(), item.getEnvironmentId());
            item.setStatus(result.isAllFlowsPassed() ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
            item.setExecutionId(result.getModuleExecutionId());
            log.info("Module {} completed with status {}", item.getTargetId(), item.getStatus());
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
            FlowExecutionResult result = executorService.runFlow(
                    item.getTargetId(), item.getEnvironmentId());
            item.setStatus(result.isAllStepsPassed() ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
            item.setExecutionId(result.getFlowExecutionId());
            log.info("Flow {} completed with status {}", item.getTargetId(), item.getStatus());
        } catch (Exception e) {
            log.error("Bulk flow execution failed for flow {}: {}", item.getTargetId(), e.getMessage());
            item.setStatus(ExecutionStatus.FAIL);
        } finally {
            item.setDurationMs(System.currentTimeMillis() - start);
            bulkJobItemRepository.save(item);
        }
    }

    /**
     * Finalize bulk job — runs on CompletableFuture thread so needs its own transaction.
     * This is why @Transactional is here specifically.
     */
    private void finalizeBulkJob(Long jobId) {
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        txTemplate.execute(status -> {
            BulkJob job = bulkJobRepository.findById(jobId).orElseThrow();
            List<BulkJobItem> items = job.getItems();

            long failedCount = items.stream()
                    .filter(i -> i.getStatus() == ExecutionStatus.FAIL).count();

            log.info("Finalizing bulk job {}: {}/{} items failed", jobId, failedCount, items.size());

            BulkJobStatus finalStatus;
            if (failedCount == 0) {
                finalStatus = BulkJobStatus.COMPLETED;
            } else if (failedCount == items.size()) {
                finalStatus = BulkJobStatus.FAILED; // all failed
            } else {
                finalStatus = BulkJobStatus.PARTIAL_FAIL; // some passed, some failed
            }

            job.setStatus(finalStatus);
            job.setFinishedAt(LocalDateTime.now());
            bulkJobRepository.save(job);
            log.info("Bulk job {} finalized — {}/{} failed, status: {}",
                    jobId, failedCount, items.size(), finalStatus);
            return null;
        });
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