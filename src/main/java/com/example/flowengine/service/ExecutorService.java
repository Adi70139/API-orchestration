package com.example.flowengine.service;

import com.example.flowengine.DTO.*;
import com.example.flowengine.DTO.FlowStepRequest;
import com.example.flowengine.constants.ExecutionStatus;
import com.example.flowengine.entity.*;
import com.example.flowengine.repository.*;
import com.example.flowengine.utils.PlaceholderUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutorService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final FlowRepository flowRepository;
    private final FlowStepRepository flowStepRepository;
    private final ModuleRepository moduleRepository;
    private final FlowExecutionRepository flowExecutionRepository;
    private final ModuleExecutionRepository moduleExecutionRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final AssertionEngine assertionEngine;
    private final EnvironmentService environmentService;
    private final SkipConditionEvaluator skipConditionEvaluator;
    private final MethodExecutorService methodExecutorService;
    private final PlaywrightExecutorService playwrightExecutorService;

    // Self-injected proxy — required so the @Transactional boundaries on the helper methods
    // below (startModuleExecution, createFlowExecutionShells, finishModuleExecution) actually
    // apply. Calling `this.someTransactionalMethod()` from inside the same class bypasses
    // Spring's AOP proxy entirely (the "self-invocation" gotcha) — going through `self` routes
    // the call back through the proxy so a real, separate transaction is opened for each one.
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private ExecutorService self;

    @org.springframework.beans.factory.annotation.Value("${flow-executor.api.core-pool-size:0}")
    private int apiCorePoolSizeConfig;
    @org.springframework.beans.factory.annotation.Value("${flow-executor.api.max-pool-size:0}")
    private int apiMaxPoolSizeConfig;
    @org.springframework.beans.factory.annotation.Value("${flow-executor.api.queue-capacity:100}")
    private int apiQueueCapacity;
    @org.springframework.beans.factory.annotation.Value("${flow-executor.ui.core-pool-size:2}")
    private int uiCorePoolSize;
    @org.springframework.beans.factory.annotation.Value("${flow-executor.ui.max-pool-size:4}")
    private int uiMaxPoolSize;
    @org.springframework.beans.factory.annotation.Value("${flow-executor.ui.queue-capacity:20}")
    private int uiQueueCapacity;

    @org.springframework.beans.factory.annotation.Value("${flow-executor.parallel-stagger-ms:300}")
    private long parallelStaggerMs;

    // Two separately-sized, BOUNDED pools instead of one shared Executors.newFixedThreadPool(5):
    //  - apiFlowExecutor: lightweight IO-bound HTTP flows, sized off available CPU cores
    //  - uiFlowExecutor: heavy Playwright/browser flows, kept small and isolated so a few stuck
    //    browser sessions can never starve API flows of a thread (bulkhead pattern)
    private java.util.concurrent.ThreadPoolExecutor apiFlowExecutor;
    private java.util.concurrent.ThreadPoolExecutor uiFlowExecutor;

    @PostConstruct
    public void init() {
        int cores = Runtime.getRuntime().availableProcessors();

        // IO-bound formula: most threads are blocked waiting on HTTP responses, not competing
        // for CPU, so we can run well beyond `cores` threads without contention. cores*4 is a
        // conservative starting multiplier — actual web servers/IO-bound services commonly run
        // much higher; this is meant to be tuned from real metrics, not treated as final.
        int apiCore = apiCorePoolSizeConfig > 0 ? apiCorePoolSizeConfig : Math.max(8, cores * 4);
        int apiMax = apiMaxPoolSizeConfig > 0 ? apiMaxPoolSizeConfig : apiCore * 2;

        apiFlowExecutor = buildBoundedPool("api-flow", apiCore, apiMax, apiQueueCapacity);
        uiFlowExecutor = buildBoundedPool("ui-flow", uiCorePoolSize, uiMaxPoolSize, uiQueueCapacity);

        log.info("[ExecutorService] apiFlowExecutor: core={} max={} queueCapacity={} (cores detected={})",
                apiCore, apiMax, apiQueueCapacity, cores);
        log.info("[ExecutorService] uiFlowExecutor: core={} max={} queueCapacity={}",
                uiCorePoolSize, uiMaxPoolSize, uiQueueCapacity);
    }

    /**
     * Builds a ThreadPoolExecutor with a BOUNDED queue (unlike Executors.newFixedThreadPool,
     * which silently uses an unbounded LinkedBlockingQueue — meaning "pool size" only caps
     * concurrency, not total accepted work; under sustained overload, queued tasks grow without
     * limit until the JVM runs out of memory, with no feedback to callers that the system is
     * overloaded). Once the queue is also full, new submissions are rejected immediately via
     * AbortPolicy — translated by submitOrReject() into a clean, caller-visible 503 instead of
     * an unbounded silent backlog.
     */
    private java.util.concurrent.ThreadPoolExecutor buildBoundedPool(String name, int core, int max, int queueCapacity) {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                core, max,
                60L, TimeUnit.SECONDS, // idle threads above core size are reclaimed after 60s
                new java.util.concurrent.ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread t = new Thread(runnable, name + "-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Submits to the given pool, converting a full-pool-and-full-queue rejection into a clean
     * IllegalStateException (mapped to HTTP 409 by GlobalExceptionHandler) instead of letting
     * RejectedExecutionException surface as a raw 500. This is the caller-visible half of the
     * backpressure story: when the system is genuinely at capacity, callers get a clear
     * "try again" signal instead of either a confusing stack trace or — with the old unbounded
     * queue — silent acceptance into an ever-growing backlog.
     */
    private void submitOrReject(java.util.concurrent.ThreadPoolExecutor pool, String workloadName, Runnable task) {
        try {
            pool.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("[ExecutorService] {} pool at capacity (active={}, queued={}, max={}) — rejecting new work",
                    workloadName, pool.getActiveCount(), pool.getQueue().size(), pool.getMaximumPoolSize());
            throw new IllegalStateException(
                    workloadName + " execution capacity is full right now — please try again shortly.");
        }
    }

    @PreDestroy
    public void shutdown() {
        shutdownPool(apiFlowExecutor, "apiFlowExecutor");
        shutdownPool(uiFlowExecutor, "uiFlowExecutor");
    }

    private void shutdownPool(java.util.concurrent.ExecutorService pool, String name) {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Pool stats for observability. Capacity numbers (cores*4, etc.) are starting points, not
     * final answers — this is what you'd actually watch in production to decide whether to
     * tune them: consistently near-zero queue size means you're over-provisioned, consistently
     * full queue + rejections means under-provisioned.
     */
    public Map<String, Object> getExecutorStats() {
        return Map.of(
                "apiFlowPool", poolStats(apiFlowExecutor),
                "uiFlowPool", poolStats(uiFlowExecutor)
        );
    }

    private Map<String, Object> poolStats(java.util.concurrent.ThreadPoolExecutor pool) {
        return Map.of(
                "activeThreads", pool.getActiveCount(),
                "poolSize", pool.getPoolSize(),
                "corePoolSize", pool.getCorePoolSize(),
                "maxPoolSize", pool.getMaximumPoolSize(),
                "queuedTasks", pool.getQueue().size(),
                "queueCapacityRemaining", pool.getQueue().remainingCapacity(),
                "completedTaskCount", pool.getCompletedTaskCount()
        );
    }

    public ModuleExecutionResult runModule(Long moduleId, Long environmentIdOverride) {
        return runModule(moduleId, environmentIdOverride, false);
    }

    /**
     * @param parallel If true, flows run concurrently instead of one-by-one. NOT the default —
     *                  flows in the same module commonly share state (same login session, same
     *                  test data/record IDs, same rate-limited environment), so concurrent runs
     *                  can turn deterministic failures into flaky, hard-to-reproduce ones. Only
     *                  opt into this for modules you've verified are made of independent flows.
     *
     *                  Implementation note on why this isn't just "submit each flow to a pool":
     *                  the original synchronous version ran the whole module — every flow,
     *                  every HTTP call — inside one long-lived transaction on one connection.
     *                  That's safe single-threaded, but worker threads get their OWN transaction/
     *                  connection (Spring's transaction binding is thread-local), and under
     *                  READ COMMITTED isolation a worker thread can't see rows the original
     *                  thread's transaction hasn't committed yet — so a naive parallel dispatch
     *                  would throw foreign-key violations the moment a worker thread tries to
     *                  insert a FlowExecution row against a ModuleExecution that isn't committed.
     *                  Fix: commit the ModuleExecution row and all FlowExecution/StepExecution
     *                  shell rows FIRST (sequentially — fast, no HTTP calls involved), THEN
     *                  dispatch only the actual execution work (the slow, IO-bound part) to the
     *                  pools, each against its own already-committed, already-visible rows.
     */
    public ModuleExecutionResult runModule(Long moduleId, Long environmentIdOverride, boolean parallel) {
        ModuleEntity module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleId));
        List<FlowDefinition> flows = flowRepository.findByModuleId(moduleId);

        long moduleStart = System.currentTimeMillis();
        Long moduleExecutionId = self.startModuleExecution(moduleId);

        // Pre-create FlowExecution + StepExecution rows for every flow, sequentially, and commit
        // each in its own short transaction — these must be durable and visible to worker
        // threads BEFORE any parallel dispatch happens.
        List<Long[]> flowExecutionIds = new ArrayList<>(); // [0]=flowId, [1]=flowExecutionId
        for (FlowDefinition flow : flows) {
            Long flowExecutionId = self.createFlowExecutionShell(flow.getId(), moduleExecutionId);
            flowExecutionIds.add(new Long[]{flow.getId(), flowExecutionId});
        }

        List<FlowExecutionResult> flowResults;
        if (parallel) {
            log.info("[ExecutorService] Module '{}' — running {} flows in PARALLEL (staggerMs={})",
                    module.getName(), flows.size(), parallelStaggerMs);
            List<java.util.concurrent.CompletableFuture<FlowExecutionResult>> futures = new ArrayList<>();
            for (int i = 0; i < flowExecutionIds.size(); i++) {
                Long[] pair = flowExecutionIds.get(i);
                Long flowId = pair[0];
                Long flowExecutionId = pair[1];
                FlowDefinition flow = flows.stream().filter(f -> f.getId().equals(flowId)).findFirst().orElseThrow();
                List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flowId);
                boolean isUI = isUIFlow(steps);
                java.util.concurrent.ThreadPoolExecutor pool = isUI ? uiFlowExecutor : apiFlowExecutor;

                // Stagger dispatch to avoid all flows hammering the same login/auth endpoint
                // at the exact same millisecond. Even 200ms is enough: the backend enforces
                // single-session-per-account and rejects 2 of 3 simultaneous logins from
                // identical credentials — confirmed in wire logs (all 3 at 09:14:59.309,
                // 2 came back 401). curl tests didn't reproduce this because shell backgrounding
                // still has a few ms of natural spread; Java threads dispatched by the same
                // pool have none. Configure via flow-executor.parallel-stagger-ms in application.yml.
                if (i > 0 && parallelStaggerMs > 0) {
                    try { Thread.sleep(parallelStaggerMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }

                java.util.concurrent.CompletableFuture<FlowExecutionResult> future = new java.util.concurrent.CompletableFuture<>();
                futures.add(future);
                try {
                    pool.execute(() -> {
                        try {
                            future.complete(isUI
                                    ? playwrightExecutorService.executeUIFlow(flow, steps, flowExecutionId)
                                    : executeExistingFlow(flowId, flowExecutionId, environmentIdOverride));
                        } catch (Exception e) {
                            log.error("Parallel flow execution {} failed: {}", flowExecutionId, e.getMessage(), e);
                            markFlowFailed(flowExecutionId, e.getMessage());
                            future.completeExceptionally(e);
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    markFlowFailed(flowExecutionId, "Execution capacity full — flow was never started");
                    future.completeExceptionally(e);
                }
            }
            flowResults = futures.stream()
                    .map(f -> {
                        try {
                            return f.join();
                        } catch (Exception e) {
                            // Already logged + marked FAIL above — build a minimal failed result so
                            // the module result list stays complete instead of throwing and losing
                            // every other flow's result.
                            FlowExecutionResult failed = new FlowExecutionResult();
                            failed.setAllStepsPassed(false);
                            return failed;
                        }
                    })
                    .toList();
        } else {
            flowResults = new ArrayList<>();
            for (Long[] pair : flowExecutionIds) {
                Long flowId = pair[0];
                Long flowExecutionId = pair[1];
                FlowDefinition flow = flows.stream().filter(f -> f.getId().equals(flowId)).findFirst().orElseThrow();
                List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flowId);
                boolean isUI = isUIFlow(steps);
                FlowExecutionResult result = isUI
                        ? playwrightExecutorService.executeUIFlow(flow, steps, flowExecutionId)
                        : executeExistingFlow(flowId, flowExecutionId, environmentIdOverride);
                flowResults.add(result);
            }
        }

        boolean allPassed = flowResults.stream().allMatch(FlowExecutionResult::isAllStepsPassed);
        self.finishModuleExecution(moduleExecutionId, allPassed);

        ModuleExecutionResult result = new ModuleExecutionResult();
        result.setModuleExecutionId(moduleExecutionId);
        result.setModuleId(moduleId);
        result.setModuleName(module.getName());
        result.setAllFlowsPassed(allPassed);
        result.setFlowResults(flowResults);
        result.setTotalDurationMs(System.currentTimeMillis() - moduleStart);
        return result;
    }

    private boolean isUIFlow(List<FlowStep> steps) {
        return !steps.isEmpty() && steps.stream().allMatch(s -> "UI".equalsIgnoreCase(s.getMethod()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistLastResponseBody(Long stepId, String responseBody) {
        flowStepRepository.updateLastResponseBody(stepId, responseBody);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long startModuleExecution(Long moduleId) {
        ModuleEntity module = moduleRepository.getReferenceById(moduleId);
        ModuleExecution moduleExecution = new ModuleExecution();
        moduleExecution.setModule(module);
        moduleExecution.setStartedAt(LocalDateTime.now());
        moduleExecution.setStatus(ExecutionStatus.IN_PROGRESS);
        return moduleExecutionRepository.save(moduleExecution).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long createFlowExecutionShell(Long flowId, Long moduleExecutionId) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
        ModuleExecution moduleExecution = moduleExecutionRepository.getReferenceById(moduleExecutionId);
        List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flowId);
        return createFlowExecution(flow, moduleExecution, steps).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishModuleExecution(Long moduleExecutionId, boolean allPassed) {
        ModuleExecution moduleExecution = moduleExecutionRepository.findById(moduleExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("ModuleExecution not found: " + moduleExecutionId));
        moduleExecution.setFinishedAt(LocalDateTime.now());
        moduleExecution.setStatus(allPassed ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
        moduleExecutionRepository.save(moduleExecution);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FlowExecutionResult runFlow(Long flowId, Long environmentIdOverride) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
        return executeFlow(flow, null, environmentIdOverride);
    }

    public FlowExecutionStartResponse startFlowAsync(Long flowId, Long environmentIdOverride) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
        List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flow.getId());

        // UI flows run synchronously in their own thread — Playwright needs a real browser window
        boolean isUIFlow = !steps.isEmpty() && steps.stream()
                .allMatch(s -> "UI".equalsIgnoreCase(s.getMethod()));
        if (isUIFlow) {
            log.info("[ExecutorService] Async UI flow '{}' — routing to Playwright", flow.getName());
            FlowExecution flowExecution = createFlowExecution(flow, null, steps);
            submitOrReject(uiFlowExecutor, "UI flow", () -> {
                try {
                    playwrightExecutorService.executeUIFlow(flow, steps, flowExecution.getId());
                } catch (Exception e) {
                    log.error("Playwright flow {} failed: {}", flowExecution.getId(), e.getMessage(), e);
                    markFlowFailed(flowExecution.getId(), e.getMessage());
                }
            });
            FlowExecutionStartResponse response = new FlowExecutionStartResponse();
            response.setFlowExecutionId(flowExecution.getId());
            response.setFlowId(flow.getId());
            response.setFlowName(flow.getName());
            response.setStatus(flowExecution.getStatus());
            return response;
        }

        FlowExecution flowExecution = createFlowExecution(flow, null, steps);

        submitOrReject(apiFlowExecutor, "API flow", () -> {
            try {
                executeExistingFlow(flow.getId(), flowExecution.getId(), environmentIdOverride);
            } catch (Exception e) {
                log.error("Async flow execution {} failed: {}", flowExecution.getId(), e.getMessage(), e);
                markFlowFailed(flowExecution.getId(), e.getMessage());
            }
        });

        FlowExecutionStartResponse response = new FlowExecutionStartResponse();
        response.setFlowExecutionId(flowExecution.getId());
        response.setFlowId(flow.getId());
        response.setFlowName(flow.getName());
        response.setStatus(flowExecution.getStatus());
        return response;
    }

    @Transactional(readOnly = true)
    public FlowExecutionStatusDTO getFlowExecutionStatus(Long flowExecutionId) {
        FlowExecution flowExecution = flowExecutionRepository.findById(flowExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("Flow execution not found: " + flowExecutionId));

        FlowExecutionStatusDTO dto = new FlowExecutionStatusDTO();
        dto.setFlowExecutionId(flowExecution.getId());
        dto.setFlowId(flowExecution.getFlow().getId());
        dto.setFlowName(flowExecution.getFlow().getName());
        dto.setStatus(flowExecution.getStatus());
        dto.setStartedAt(flowExecution.getStartedAt());
        dto.setFinishedAt(flowExecution.getFinishedAt());
        dto.setSteps(stepExecutionRepository.findByFlowExecutionIdOrderByStepOrderAsc(flowExecutionId)
                .stream()
                .map(this::mapToStepStatusDTO)
                .collect(Collectors.toList()));
        return dto;
    }

    private FlowExecutionResult executeFlow(FlowDefinition flow, ModuleExecution moduleExecution, Long environmentIdOverride) {
        List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flow.getId());

        // If all steps are UI type, delegate entirely to Playwright executor
        boolean isUIFlow = !steps.isEmpty() && steps.stream()
                .allMatch(s -> "UI".equalsIgnoreCase(s.getMethod()));
        if (isUIFlow) {
            log.info("[ExecutorService] Flow '{}' is a UI automation flow — routing to Playwright", flow.getName());
            FlowExecution flowExecution = createFlowExecution(flow, moduleExecution, steps);
            return playwrightExecutorService.executeUIFlow(flow, steps, flowExecution.getId());
        }

        FlowExecution flowExecution = createFlowExecution(flow, moduleExecution, steps);
        return executeExistingFlow(flow.getId(), flowExecution.getId(), environmentIdOverride);
    }

    private FlowExecutionResult executeExistingFlow(Long flowId, Long flowExecutionId, Long environmentIdOverride) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
        FlowExecution flowExecution = flowExecutionRepository.findById(flowExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("Flow execution not found: " + flowExecutionId));

        Map<String, String> envVariables = new LinkedHashMap<>();
        Long envId = environmentIdOverride != null
                ? environmentIdOverride
                : (flow.getDefaultEnvironment() != null ? flow.getDefaultEnvironment().getId() : null);

        if (envId != null) {
            try {
                envVariables = environmentService.getDecryptedVariables(envId);
                log.info("Loaded {} env variables for flow '{}'", envVariables.size(), flow.getName());
            } catch (Exception e) {
                log.warn("Could not load env {} for flow '{}' — running without env variables: {}",
                        envId, flow.getName(), e.getMessage());
            }
        }

        List<String> previousResponses = new ArrayList<>();
        Map<Long, String> responsesByStepId = new HashMap<>();
        if (!envVariables.isEmpty()) {
            try {
                previousResponses.add(objectMapper.writeValueAsString(envVariables));
                log.info("Seeded {} env variables into context for flow '{}'", envVariables.size(), flow.getName());
            } catch (Exception e) {
                log.warn("Could not seed env variables into context for flow '{}'", flow.getName());
            }
        }

        List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flow.getId());
        Map<Long, StepExecution> stepExecutions = stepExecutionRepository
                .findByFlowExecutionIdOrderByStepOrderAsc(flowExecution.getId())
                .stream()
                .collect(Collectors.toMap(StepExecution::getStepId, stepExecution -> stepExecution));

        List<StepExecutionResult> stepResults = new ArrayList<>();
        long flowStart = System.currentTimeMillis();
        boolean allPassed = true;

        for (FlowStep step : steps) {
            StepExecution stepExecution = stepExecutions.get(step.getId());
            if (stepExecution == null) {
                stepExecution = createStepExecution(flowExecution, step);
            }

            // Evaluate skip condition before executing the step
            if (step.getSkipConditionJson() != null) {
                try {
                    FlowStepRequest.SkipConditionRequest skipCondition = objectMapper.readValue(
                            step.getSkipConditionJson(), FlowStepRequest.SkipConditionRequest.class);
                    String skipReason = skipConditionEvaluator.shouldSkip(skipCondition, previousResponses);
                    if (skipReason != null) {
                        log.info("Skipping step '{}' (order {}) — {}", step.getName(), step.getStepOrder(), skipReason);
                        markStepSkipped(stepExecution, skipReason);

                        StepExecutionResult skippedResult = new StepExecutionResult();
                        skippedResult.setStepId(step.getId());
                        skippedResult.setStepName(step.getName());
                        skippedResult.setStepOrder(step.getStepOrder());
                        skippedResult.setSkipped(true);
                        skippedResult.setSkipReason(skipReason);
                        skippedResult.setSuccess(true); // skipped = not a failure
                        stepResults.add(skippedResult);
                        continue; // bypass execution, don't add to previousResponses
                    }
                } catch (Exception e) {
                    log.warn("Could not evaluate skipCondition for step '{}': {}", step.getName(), e.getMessage());
                }
            }

            markStepInProgress(stepExecution);

            // Run pre-step methods and inject their output into the placeholder context
            List<String> previousResponsesWithMethods = new ArrayList<>(previousResponses);
            try {
                MethodExecutorService.StepMethodContext methodContext =
                        methodExecutorService.runMethodsForStep(step.getId(), previousResponses);
                if (!methodContext.mergedOutput().isEmpty()) {
                    // Serialize method output as JSON and inject as an additional context entry
                    // Keys are already prefixed with "method." — e.g. {"method.result": "42"}
                    previousResponsesWithMethods.add(
                            objectMapper.writeValueAsString(methodContext.mergedOutput()));
                    log.info("Injected {} method output(s) into context for step '{}'",
                            methodContext.mergedOutput().size(), step.getName());
                }
            } catch (Exception e) {
                log.warn("Could not run pre-step methods for step '{}': {}", step.getName(), e.getMessage());
            }

            StepExecutionResult stepResult = Boolean.TRUE.equals(step.getPollUntilSuccess())
                    ? executeStepWithPolling(step, previousResponsesWithMethods, responsesByStepId, stepExecution)
                    : executeStepWithRetry(step, previousResponsesWithMethods, responsesByStepId, stepExecution);
            markStepCompleted(stepExecution, stepResult);
            stepResults.add(stepResult);

            if (stepResult.isSuccess()) {
                previousResponses.add(stepResult.getResponseBody());
                responsesByStepId.put(step.getId(), stepResult.getResponseBody());
                // Update lastResponseBody for LLM generators via a targeted UPDATE, not a full
                // entity save. The original code did flowStepRepository.save(step) here — that
                // rewrites the entire row from the in-memory entity state, which is a
                // concurrent write bug: all parallel flow executions share the same FlowStep
                // rows (same step IDs), so three simultaneous save() calls on the same entity
                // race each other, and the last writer wins regardless of which flow's
                // response body is actually correct for that step.
                // A targeted single-column UPDATE is atomic per row and doesn't risk
                // overwriting unrelated fields from a stale in-memory entity snapshot.
                try {
                    self.persistLastResponseBody(step.getId(), stepResult.getResponseBody());
                } catch (Exception e) {
                    log.warn("Could not persist lastResponseBody for step '{}': {}", step.getName(), e.getMessage());
                }
            } else {
                allPassed = false;
                log.warn("Step '{}' failed in flow '{}': {}", step.getName(), flow.getName(), stepResult.getErrorMessage());
                break;
            }
        }

        flowExecutionRepository.updateExecutionStatus(
                flowExecution.getId(),
                allPassed ? ExecutionStatus.PASS : ExecutionStatus.FAIL,
                LocalDateTime.now());

        FlowExecutionResult result = new FlowExecutionResult();
        result.setFlowExecutionId(flowExecution.getId());
        result.setFlowId(flow.getId());
        result.setFlowName(flow.getName());
        result.setAllStepsPassed(allPassed);
        result.setStepResults(stepResults);
        result.setTotalDurationMs(System.currentTimeMillis() - flowStart);
        return result;
    }

    private FlowExecution createFlowExecution(FlowDefinition flow, ModuleExecution moduleExecution, List<FlowStep> steps) {
        FlowExecution flowExecution = new FlowExecution();
        flowExecution.setFlow(flow);
        flowExecution.setModuleExecution(moduleExecution);
        flowExecution.setStartedAt(LocalDateTime.now());
        flowExecution.setStatus(ExecutionStatus.IN_PROGRESS);
        flowExecution = flowExecutionRepository.save(flowExecution);

        for (FlowStep step : steps) {
            createStepExecution(flowExecution, step);
        }

        return flowExecution;
    }

    private StepExecution createStepExecution(FlowExecution flowExecution, FlowStep step) {
        StepExecution stepExecution = new StepExecution();
        stepExecution.setFlowExecution(flowExecution);
        stepExecution.setStepId(step.getId());
        stepExecution.setStepName(step.getName());
        stepExecution.setStepOrder(step.getStepOrder());
        stepExecution.setStatus(ExecutionStatus.PENDING);
        return stepExecutionRepository.save(stepExecution);
    }

    private void markStepInProgress(StepExecution stepExecution) {
        stepExecution.setStatus(ExecutionStatus.IN_PROGRESS);
        stepExecution.setStartedAt(LocalDateTime.now());
        stepExecutionRepository.save(stepExecution);
    }

    private void markStepCompleted(StepExecution stepExecution, StepExecutionResult stepResult) {
        stepExecution.setStatus(stepResult.isSuccess() ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
        stepExecution.setFinishedAt(LocalDateTime.now());
        stepExecutionRepository.save(stepExecution);
    }

    private void markStepSkipped(StepExecution stepExecution, String reason) {
        stepExecution.setStatus(ExecutionStatus.SKIPPED);
        stepExecution.setStartedAt(LocalDateTime.now());
        stepExecution.setFinishedAt(LocalDateTime.now());
        stepExecution.setSuccess(false); // skipped steps don't count as success in DB
        stepExecution.setErrorMessage("SKIPPED: " + reason);
        stepExecution.setDurationMs(0L);
        stepExecutionRepository.save(stepExecution);
    }

    private void markFlowFailed(Long flowExecutionId, String message) {
        flowExecutionRepository.updateExecutionStatus(
                flowExecutionId, ExecutionStatus.FAIL, LocalDateTime.now());

        stepExecutionRepository.findByFlowExecutionIdOrderByStepOrderAsc(flowExecutionId).stream()
                .filter(stepExecution -> stepExecution.getStatus() == ExecutionStatus.IN_PROGRESS)
                .forEach(stepExecution -> {
                    stepExecution.setStatus(ExecutionStatus.FAIL);
                    stepExecution.setSuccess(false);
                    stepExecution.setErrorMessage(message);
                    stepExecution.setFinishedAt(LocalDateTime.now());
                    stepExecutionRepository.save(stepExecution);
                });
    }

    private FlowExecutionStatusDTO.StepStatusDTO mapToStepStatusDTO(StepExecution stepExecution) {
        FlowExecutionStatusDTO.StepStatusDTO dto = new FlowExecutionStatusDTO.StepStatusDTO();
        dto.setStepExecutionId(stepExecution.getId());
        dto.setStepId(stepExecution.getStepId());
        dto.setStepName(stepExecution.getStepName());
        dto.setStepOrder(stepExecution.getStepOrder());
        dto.setStatus(stepExecution.getStatus());
        dto.setStatusCode(stepExecution.getStatusCode());
        dto.setErrorMessage(stepExecution.getErrorMessage());
        dto.setDurationMs(stepExecution.getDurationMs());
        dto.setTotalAttempts(stepExecution.getTotalAttempts());
        dto.setTotalPollAttempts(stepExecution.getTotalPollAttempts());
        dto.setPollingTimedOut(stepExecution.getPollingTimedOut());
        dto.setStartedAt(stepExecution.getStartedAt());
        dto.setFinishedAt(stepExecution.getFinishedAt());
        return dto;
    }

    /**
     * Wraps executeStep with retry logic.
     * Retries only on network errors (Exception) and 5xx responses.
     * Never retries 4xx (client error) or assertion failures (deterministic).
     */
    private StepExecutionResult executeStepWithRetry(FlowStep step, List<String> previousResponses,
                                                     Map<Long, String> responsesByStepId,
                                                     StepExecution stepExecution) {
        // Null-safe — existing steps in DB may have null if created before retry columns were added
        int retryCount = step.getRetryCount() != null ? step.getRetryCount() : 0;
        int delayMs = step.getRetryDelayMs() != null ? step.getRetryDelayMs() : 1000;
        int initialDelayMs = step.getInitialDelayMs() != null ? step.getInitialDelayMs() : 0;
        int maxAttempts = 1 + Math.max(0, retryCount);

        List<RetryAttemptResult> attempts = new ArrayList<>();
        StepExecutionResult lastResult = null;

        // Initial delay — wait before the very first attempt
        if (initialDelayMs > 0) {
            log.info("Initial delay {}ms before first attempt of step '{}'", initialDelayMs, step.getName());
            try {
                Thread.sleep(initialDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                log.info("Retrying step '{}' — attempt {}/{} after {}ms delay",
                        step.getName(), attempt, maxAttempts, delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            lastResult = executeStep(step, previousResponses, responsesByStepId, stepExecution);

            // Record this attempt
            RetryAttemptResult attemptResult = new RetryAttemptResult();
            attemptResult.setAttempt(attempt);
            attemptResult.setStatusCode(lastResult.getStatusCode());
            attemptResult.setResponseBody(lastResult.getResponseBody());
            attemptResult.setErrorMessage(lastResult.getErrorMessage());
            attemptResult.setSuccess(lastResult.isSuccess());
            attemptResult.setDurationMs(lastResult.getDurationMs());
            attempts.add(attemptResult);

            if (lastResult.isSuccess()) {
                if (attempt > 1) {
                    log.info("Step '{}' succeeded on attempt {}/{}", step.getName(), attempt, maxAttempts);
                }
                break;
            }

            // Don't retry 4xx — client error, won't self-heal
            if (lastResult.getStatusCode() != null
                    && lastResult.getStatusCode() >= 400
                    && lastResult.getStatusCode() < 500) {
                log.warn("Step '{}' returned {} — not retrying (4xx client error)",
                        step.getName(), lastResult.getStatusCode());
                break;
            }

            // Don't retry assertion failures — server responded correctly, they're deterministic
            if (lastResult.getErrorMessage() != null
                    && lastResult.getErrorMessage().startsWith("Assertion failures")) {
                log.warn("Step '{}' failed assertions — not retrying", step.getName());
                break;
            }

            if (attempt < maxAttempts) {
                log.warn("Step '{}' failed on attempt {}/{}: {}",
                        step.getName(), attempt, maxAttempts, lastResult.getErrorMessage());
            }
        }

        if (attempts.size() > 1) {
            log.error("Step '{}' completed after {} attempts — final status: {}",
                    step.getName(), attempts.size(), lastResult.isSuccess() ? "SUCCESS" : "FAILED");
        }

        // Persist retry metadata to StepExecution
        stepExecution.setTotalAttempts(attempts.size());
        if (attempts.size() > 1) {
            try {
                stepExecution.setRetryAttemptsJson(objectMapper.writeValueAsString(attempts));
            } catch (Exception ignored) {}
        }
        stepExecutionRepository.save(stepExecution);

        // Attach retry attempts to result for immediate API response
        if (attempts.size() > 1) {
            lastResult.setRetryAttempts(attempts);
        }
        return lastResult;
    }

    /**
     * Polling mode — for async/workflow APIs that return non-2xx until the task is ready.
     * Keeps hitting the API at pollIntervalMs intervals until:
     *   - Response status matches pollExpectedStatus → PASS
     *   - pollMaxAttempts exhausted → FAIL with "polling timed out"
     * Unlike retry, 4xx responses are expected and do NOT stop polling.
     */
    private StepExecutionResult executeStepWithPolling(FlowStep step, List<String> previousResponses,
                                                       Map<Long, String> responsesByStepId,
                                                       StepExecution stepExecution) {
        int maxAttempts = step.getPollMaxAttempts() != null ? step.getPollMaxAttempts() : 10;
        int intervalMs = step.getPollIntervalMs() != null ? step.getPollIntervalMs() : 5000;
        int expectedStatus = step.getPollExpectedStatus() != null ? step.getPollExpectedStatus() : 200;
        int initialDelayMs = step.getInitialDelayMs() != null ? step.getInitialDelayMs() : 0;

        List<PollAttemptResult> attempts = new ArrayList<>();
        StepExecutionResult lastResult = null;

        if (initialDelayMs > 0) {
            log.info("Initial delay {}ms before polling step '{}'", initialDelayMs, step.getName());
            try { Thread.sleep(initialDelayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                log.info("Polling step '{}' — attempt {}/{}, waiting {}ms", step.getName(), attempt, maxAttempts, intervalMs);
                try { Thread.sleep(intervalMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }

            lastResult = executeStep(step, previousResponses, responsesByStepId, stepExecution);

            PollAttemptResult pollAttempt = new PollAttemptResult();
            pollAttempt.setAttempt(attempt);
            pollAttempt.setStatusCode(lastResult.getStatusCode());
            pollAttempt.setResponseBody(lastResult.getResponseBody());
            pollAttempt.setDurationMs(lastResult.getDurationMs());

            // Polling success: status matches expected
            boolean conditionMet = lastResult.getStatusCode() != null
                    && lastResult.getStatusCode() == expectedStatus;
            pollAttempt.setSuccess(conditionMet);
            attempts.add(pollAttempt);

            if (conditionMet) {
                log.info("Polling step '{}' completed successfully on attempt {}/{}", step.getName(), attempt, maxAttempts);
                // Override success on the result — polling condition met
                lastResult.setSuccess(true);
                lastResult.setErrorMessage(null);
                stepExecution.setSuccess(true);
                stepExecution.setErrorMessage(null);
                stepExecution.setPollingTimedOut(false);
                break;
            }

            log.info("Polling step '{}' attempt {}/{} — got {} (expecting {})",
                    step.getName(), attempt, maxAttempts,
                    lastResult.getStatusCode(), expectedStatus);
        }

        // If we exhausted all attempts without meeting condition
        if (!attempts.isEmpty() && !attempts.get(attempts.size() - 1).isSuccess()) {
            String msg = "Polling timed out after " + attempts.size() + " attempts — "
                    + "last status: " + (lastResult.getStatusCode() != null ? lastResult.getStatusCode() : "no response")
                    + " (expected " + expectedStatus + ")";
            log.error("Step '{}': {}", step.getName(), msg);
            lastResult.setSuccess(false);
            lastResult.setErrorMessage(msg);
            stepExecution.setSuccess(false);
            stepExecution.setErrorMessage(msg);
            stepExecution.setPollingTimedOut(true);
        }

        // Persist poll metadata
        stepExecution.setTotalPollAttempts(attempts.size());
        try {
            stepExecution.setPollAttemptsJson(objectMapper.writeValueAsString(attempts));
        } catch (Exception ignored) {}
        stepExecutionRepository.save(stepExecution);

        lastResult.setPollAttempts(attempts);
        lastResult.setTotalPollAttempts(attempts.size());
        return lastResult;
    }

    private StepExecutionResult executeStep(FlowStep step, List<String> previousResponses,
                                            Map<Long, String> responsesByStepId,
                                            StepExecution stepExecution) {
        long start = System.currentTimeMillis();

        // UI automation steps are Playwright-only — skip HTTP execution entirely
        if ("UI".equalsIgnoreCase(step.getMethod())) {
            stepExecution.setStatusCode(0);
            stepExecution.setResponseBody("UI automation step — run the Playwright script to execute.");
            stepExecution.setSuccess(true);
            stepExecution.setDurationMs(0L);
            stepExecutionRepository.save(stepExecution);

            StepExecutionResult result = new StepExecutionResult();
            result.setStepId(step.getId());
            result.setStepName(step.getName());
            result.setSuccess(true);
            result.setStatusCode(0);
            result.setResponseBody("UI step — Playwright only");
            result.setDurationMs(0L);
            return result;
        }

        try {
            String resolvedUrl = PlaceholderUtils.resolve(step.getUrl(), previousResponses);
            String resolvedHeaders = PlaceholderUtils.resolve(step.getHeadersJson(), previousResponses);
            String resolvedBody = resolveRequestBody(step, previousResponses, responsesByStepId);

            stepExecution.setResolvedUrl(resolvedUrl);
            stepExecution.setResolvedHeadersJson(resolvedHeaders);
            stepExecution.setResolvedBodyJson(resolvedBody);

            Request.Builder requestBuilder = new Request.Builder().url(resolvedUrl);

            if (resolvedHeaders != null && !resolvedHeaders.isBlank()) {
                Map<String, String> headers = objectMapper.readValue(resolvedHeaders, Map.class);
                headers.forEach(requestBuilder::addHeader);
            }

            String method = step.getMethod().toUpperCase();
            RequestBody body = null;
            if (requiresBody(method)) {
                body = RequestBody.create(resolvedBody != null ? resolvedBody : "{}", JSON_MEDIA_TYPE);
            }
            requestBuilder.method(method, body);

            Request request = requestBuilder.build();

            // Wire-level log — logs exact bytes going out so we can diff concurrent requests.
            // Remove once the parallel-flow auth failure is root-caused.
            if (log.isDebugEnabled()) {
                String bodyPreview = "(no body)";
                if (request.body() != null) {
                    try {
                        okio.Buffer buf = new okio.Buffer();
                        request.body().writeTo(buf);
                        bodyPreview = buf.readUtf8();
                    } catch (Exception ignored) {}
                }
                log.debug("[WIRE] thread={} step='{}' {} {} body={}",
                        Thread.currentThread().getName(), step.getName(),
                        request.method(), request.url(), bodyPreview);
            }

            try (Response response = okHttpClient.newCall(request).execute()) {
                long duration = System.currentTimeMillis() - start;
                String responseBody = response.body() != null ? response.body().string() : "";
                boolean success = response.isSuccessful();
                int statusCode = response.code();

                stepExecution.setStatusCode(statusCode);
                stepExecution.setResponseBody(responseBody);
                stepExecution.setSuccess(success);
                stepExecution.setErrorMessage(success ? null : "HTTP " + statusCode);
                stepExecution.setDurationMs(duration);

                List<AssertionResult> assertionResults = new ArrayList<>();
                if (step.getAssertionsJson() != null) {
                    try {
                        FlowStepRequest.AssertionsRequest assertions = objectMapper.readValue(
                                step.getAssertionsJson(), FlowStepRequest.AssertionsRequest.class);

                        // Seed the current step's resolved request body into context so
                        // assertions can reference {processId} etc. from the request they just sent
                        List<String> assertionContext = new ArrayList<>(previousResponses);
                        if (resolvedBody != null && !resolvedBody.isBlank()) {
                            assertionContext.add(resolvedBody);
                        }

                        assertionResults = assertionEngine.evaluate(assertions, statusCode, responseBody, assertionContext);

                        // Split critical vs non-critical failures
                        // Non-critical: record as FAIL but don't stop the flow
                        // Critical: stop the flow (default behaviour)
                        List<AssertionResult> criticalFailures = assertionResults.stream()
                                .filter(a -> !a.isPassed() && a.isCritical())
                                .collect(Collectors.toList());

                        List<AssertionResult> nonCriticalFailures = assertionResults.stream()
                                .filter(a -> !a.isPassed() && !a.isCritical())
                                .collect(Collectors.toList());

                        if (!nonCriticalFailures.isEmpty()) {
                            log.warn("Step '{}' has {} non-critical assertion failure(s) — continuing flow",
                                    step.getName(), nonCriticalFailures.size());
                        }

                        if (!criticalFailures.isEmpty()) {
                            success = false;
                            String failedAssertions = criticalFailures.stream()
                                    .map(a -> a.getPath() + ": " + a.getMessage())
                                    .collect(Collectors.joining("; "));
                            stepExecution.setErrorMessage("Assertion failures: " + failedAssertions);
                        }
                    } catch (Exception e) {
                        log.warn("Could not evaluate assertions for step {}: {}", step.getName(), e.getMessage());
                    }
                }

                if (!assertionResults.isEmpty()) {
                    try {
                        stepExecution.setAssertionResultsJson(objectMapper.writeValueAsString(assertionResults));
                    } catch (Exception ignored) {}
                }

                stepExecution.setSuccess(success);
                stepExecution.setStatusCode(statusCode);
                stepExecution.setResponseBody(responseBody);
                stepExecution.setDurationMs(duration);
                // Note: stepExecution is saved by executeStepWithRetry after retry loop completes

                StepExecutionResult result = new StepExecutionResult();
                result.setStepId(step.getId());
                result.setStepName(step.getName());
                result.setStepOrder(step.getStepOrder());
                result.setResolvedUrl(resolvedUrl);
                result.setResolvedHeadersJson(resolvedHeaders);
                result.setResolvedBodyJson(resolvedBody);
                result.setStatusCode(statusCode);
                result.setResponseBody(responseBody);
                result.setSuccess(success);
                result.setDurationMs(duration);
                result.setAssertionResults(assertionResults.isEmpty() ? null : assertionResults);
                result.setErrorMessage(stepExecution.getErrorMessage());
                return result;
            }

        } catch (IllegalArgumentException e) {
            long duration = System.currentTimeMillis() - start;
            stepExecution.setSuccess(false);
            stepExecution.setErrorMessage(e.getMessage());
            stepExecution.setDurationMs(duration);

            StepExecutionResult result = new StepExecutionResult();
            result.setStepId(step.getId());
            result.setStepName(step.getName());
            result.setStepOrder(step.getStepOrder());
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setDurationMs(duration);
            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Exception executing step '{}': {}", step.getName(), e.getMessage());
            stepExecution.setSuccess(false);
            stepExecution.setErrorMessage(e.getMessage());
            stepExecution.setDurationMs(duration);

            StepExecutionResult result = new StepExecutionResult();
            result.setStepId(step.getId());
            result.setStepName(step.getName());
            result.setStepOrder(step.getStepOrder());
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setDurationMs(duration);
            return result;
        }
    }

    private String resolveRequestBody(FlowStep step, List<String> previousResponses,
                                      Map<Long, String> responsesByStepId) throws Exception {
        String resolvedOverrides = PlaceholderUtils.resolve(step.getBodyJson(), previousResponses);
        if (step.getBodySourceStepId() == null) {
            return resolvedOverrides;
        }

        String sourceBody = responsesByStepId.get(step.getBodySourceStepId());
        if (sourceBody == null || sourceBody.isBlank()) {
            throw new IllegalArgumentException(
                    "No successful response body available from source step: " + step.getBodySourceStepId());
        }

        JsonNode source = objectMapper.readTree(sourceBody);
        if (resolvedOverrides == null || resolvedOverrides.isBlank()) {
            return objectMapper.writeValueAsString(source);
        }

        JsonNode overrides = objectMapper.readTree(resolvedOverrides);
        if (!source.isObject() || !overrides.isObject()) {
            return objectMapper.writeValueAsString(overrides);
        }

        JsonNode merged = source.deepCopy();
        deepMerge((com.fasterxml.jackson.databind.node.ObjectNode) merged,
                (com.fasterxml.jackson.databind.node.ObjectNode) overrides);
        return objectMapper.writeValueAsString(merged);
    }

    private void deepMerge(com.fasterxml.jackson.databind.node.ObjectNode target,
                           com.fasterxml.jackson.databind.node.ObjectNode overrides) {
        overrides.fields().forEachRemaining(entry -> {
            JsonNode existing = target.get(entry.getKey());
            JsonNode override = entry.getValue();
            if (existing != null && existing.isObject() && override.isObject()) {
                deepMerge((com.fasterxml.jackson.databind.node.ObjectNode) existing,
                        (com.fasterxml.jackson.databind.node.ObjectNode) override);
            } else {
                target.set(entry.getKey(), override);
            }
        });
    }

    private boolean requiresBody(String method) {
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
    }
}
