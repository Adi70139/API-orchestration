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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BulkExecutionService {

    private final ExecutorService executorService;
    private final BulkJobRepository bulkJobRepository;
    private final BulkJobItemRepository bulkJobItemRepository;
    private final ModuleRepository moduleRepository;
    private final FlowRepository flowRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${bulk.executor.thread-pool-size:10}")
    private int threadPoolSize;

    // Dedicated pool — never shares with ForkJoinPool or scheduler threads
    private ThreadPoolExecutor bulkExecutor;

    public BulkExecutionService(ExecutorService executorService,
                                BulkJobRepository bulkJobRepository,
                                BulkJobItemRepository bulkJobItemRepository,
                                ModuleRepository moduleRepository,
                                FlowRepository flowRepository,
                                PlatformTransactionManager transactionManager) {
        this.executorService = executorService;
        this.bulkJobRepository = bulkJobRepository;
        this.bulkJobItemRepository = bulkJobItemRepository;
        this.moduleRepository = moduleRepository;
        this.flowRepository = flowRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void init() {
        bulkExecutor = new ThreadPoolExecutor(
                threadPoolSize,
                threadPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100), // bounded queue — rejects if overloaded
                new ThreadFactory() {
                    private int count = 0;
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "bulk-executor-" + ++count);
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // back-pressure: caller runs if queue full
        );
        log.info("BulkExecutionService initialized with thread pool size {}", threadPoolSize);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down bulk executor — waiting for active jobs to complete");
        bulkExecutor.shutdown();
        try {
            if (!bulkExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                bulkExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            bulkExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public BulkJobResult startModuleBulkJob(List<Long> moduleIds, List<Long> envIds) {
        BulkJob job = createJob("MODULE");

        List<BulkJobItem> items = new ArrayList<>();
        for (int i = 0; i < moduleIds.size(); i++) {
            Long moduleId = moduleIds.get(i);
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
                .map(item -> CompletableFuture.runAsync(() -> runModuleItem(item), bulkExecutor))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRunAsync(() -> finalizeBulkJob(job.getId()), bulkExecutor);

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
                .map(item -> CompletableFuture.runAsync(() -> runFlowItem(item), bulkExecutor))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRunAsync(() -> finalizeBulkJob(job.getId()), bulkExecutor);

        return mapToResult(job, items);
    }

    public BulkJobResult getJobStatus(Long bulkJobId) {
        BulkJob job = bulkJobRepository.findById(bulkJobId)
                .orElseThrow(() -> new IllegalArgumentException("Bulk job not found: " + bulkJobId));
        return mapToResult(job, job.getItems());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Long resolveEnvId(List<Long> envIds, int index) {
        if (envIds == null || envIds.isEmpty()) return null;
        if (index >= envIds.size()) return null;
        return envIds.get(index);
    }

    private void runModuleItem(BulkJobItem item) {
        long start = System.currentTimeMillis();
        try {
            ModuleExecutionResult result = executorService.runModule(
                    item.getTargetId(), item.getEnvironmentId());
            item.setStatus(result.isAllFlowsPassed() ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
            item.setExecutionId(result.getModuleExecutionId());
            log.info("Module {} completed — {}", item.getTargetId(), item.getStatus());
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
            log.info("Flow {} completed — {}", item.getTargetId(), item.getStatus());
        } catch (Exception e) {
            log.error("Bulk flow execution failed for flow {}: {}", item.getTargetId(), e.getMessage());
            item.setStatus(ExecutionStatus.FAIL);
        } finally {
            item.setDurationMs(System.currentTimeMillis() - start);
            bulkJobItemRepository.save(item);
        }
    }

    private void finalizeBulkJob(Long jobId) {
        transactionTemplate.execute(status -> {
            BulkJob job = bulkJobRepository.findById(jobId).orElseThrow();
            List<BulkJobItem> items = job.getItems();

            long failedCount = items.stream()
                    .filter(i -> i.getStatus() == ExecutionStatus.FAIL).count();

            BulkJobStatus finalStatus;
            if (failedCount == 0) {
                finalStatus = BulkJobStatus.COMPLETED;
            } else if (failedCount == items.size()) {
                finalStatus = BulkJobStatus.FAILED;
            } else {
                finalStatus = BulkJobStatus.PARTIAL_FAIL;
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