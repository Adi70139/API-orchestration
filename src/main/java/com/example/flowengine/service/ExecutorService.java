package com.example.flowengine.service;

import com.example.flowengine.DTO.*;
import com.example.flowengine.constants.ExecutionStatus;
import com.example.flowengine.entity.*;
import com.example.flowengine.repository.*;
import com.example.flowengine.utils.PlaceholderUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
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

    private java.util.concurrent.ExecutorService asyncFlowExecutor;

    @PostConstruct
    public void init() {
        asyncFlowExecutor = Executors.newFixedThreadPool(5);
    }

    @PreDestroy
    public void shutdown() {
        asyncFlowExecutor.shutdown();
        try {
            if (!asyncFlowExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                asyncFlowExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncFlowExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ModuleExecutionResult runModule(Long moduleId, Long environmentIdOverride) {
        ModuleEntity module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleId));

        List<FlowDefinition> flows = flowRepository.findByModuleId(moduleId);

        ModuleExecution moduleExecution = new ModuleExecution();
        moduleExecution.setModule(module);
        moduleExecution.setStartedAt(LocalDateTime.now());
        moduleExecution.setStatus(ExecutionStatus.IN_PROGRESS);
        moduleExecution = moduleExecutionRepository.save(moduleExecution);

        List<FlowExecutionResult> flowResults = new ArrayList<>();
        long moduleStart = System.currentTimeMillis();
        boolean allPassed = true;

        for (FlowDefinition flow : flows) {
            FlowExecutionResult flowResult = executeFlow(flow, moduleExecution, environmentIdOverride);
            flowResults.add(flowResult);
            if (!flowResult.isAllStepsPassed()) allPassed = false;
        }

        moduleExecution.setFinishedAt(LocalDateTime.now());
        moduleExecution.setStatus(allPassed ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
        moduleExecutionRepository.save(moduleExecution);

        ModuleExecutionResult result = new ModuleExecutionResult();
        result.setModuleExecutionId(moduleExecution.getId());
        result.setModuleId(moduleId);
        result.setModuleName(module.getName());
        result.setAllFlowsPassed(allPassed);
        result.setFlowResults(flowResults);
        result.setTotalDurationMs(System.currentTimeMillis() - moduleStart);
        return result;
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
        FlowExecution flowExecution = createFlowExecution(flow, null, steps);

        CompletableFuture.runAsync(() -> {
            try {
                executeExistingFlow(flow.getId(), flowExecution.getId(), environmentIdOverride);
            } catch (Exception e) {
                log.error("Async flow execution {} failed: {}", flowExecution.getId(), e.getMessage(), e);
                markFlowFailed(flowExecution.getId(), e.getMessage());
            }
        }, asyncFlowExecutor);

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
            markStepInProgress(stepExecution);

            StepExecutionResult stepResult = Boolean.TRUE.equals(step.getPollUntilSuccess())
                    ? executeStepWithPolling(step, previousResponses, stepExecution)
                    : executeStepWithRetry(step, previousResponses, stepExecution);
            markStepCompleted(stepExecution, stepResult);
            stepResults.add(stepResult);

            if (stepResult.isSuccess()) {
                previousResponses.add(stepResult.getResponseBody());
            } else {
                allPassed = false;
                log.warn("Step '{}' failed in flow '{}': {}", step.getName(), flow.getName(), stepResult.getErrorMessage());
                break;
            }
        }

        flowExecution.setFinishedAt(LocalDateTime.now());
        flowExecution.setStatus(allPassed ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
        flowExecutionRepository.save(flowExecution);

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

    private void markFlowFailed(Long flowExecutionId, String message) {
        flowExecutionRepository.findById(flowExecutionId).ifPresent(flowExecution -> {
            flowExecution.setStatus(ExecutionStatus.FAIL);
            flowExecution.setFinishedAt(LocalDateTime.now());
            flowExecutionRepository.save(flowExecution);
        });

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
    private StepExecutionResult executeStepWithRetry(FlowStep step, List<String> previousResponses, StepExecution stepExecution) {
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

            lastResult = executeStep(step, previousResponses, stepExecution);

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
    private StepExecutionResult executeStepWithPolling(FlowStep step, List<String> previousResponses, StepExecution stepExecution) {
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

            lastResult = executeStep(step, previousResponses, stepExecution);

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

    private StepExecutionResult executeStep(FlowStep step, List<String> previousResponses, StepExecution stepExecution) {
        long start = System.currentTimeMillis();

        try {
            String resolvedUrl = PlaceholderUtils.resolve(step.getUrl(), previousResponses);
            String resolvedHeaders = PlaceholderUtils.resolve(step.getHeadersJson(), previousResponses);
            String resolvedBody = PlaceholderUtils.resolve(step.getBodyJson(), previousResponses);

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

            try (Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
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

    private boolean requiresBody(String method) {
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
    }
}
