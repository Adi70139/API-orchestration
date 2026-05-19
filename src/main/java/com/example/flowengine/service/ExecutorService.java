package com.example.flowengine.service;

import com.example.flowengine.DTO.*;
import com.example.flowengine.constants.ExecutionStatus;
import com.example.flowengine.entity.*;
import com.example.flowengine.repository.*;
import com.example.flowengine.utils.PlaceholderUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private FlowExecutionResult executeFlow(FlowDefinition flow, ModuleExecution moduleExecution, Long environmentIdOverride) {

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

        FlowExecution flowExecution = new FlowExecution();
        flowExecution.setFlow(flow);
        flowExecution.setModuleExecution(moduleExecution);
        flowExecution.setStartedAt(LocalDateTime.now());
        flowExecution.setStatus(ExecutionStatus.IN_PROGRESS);
        flowExecution = flowExecutionRepository.save(flowExecution);

        List<StepExecutionResult> stepResults = new ArrayList<>();
        long flowStart = System.currentTimeMillis();
        boolean allPassed = true;

        for (FlowStep step : steps) {
            StepExecution stepExecution = new StepExecution();
            stepExecution.setFlowExecution(flowExecution);
            stepExecution.setStepId(step.getId());
            stepExecution.setStepName(step.getName());
            stepExecution.setStepOrder(step.getStepOrder());

            StepExecutionResult stepResult = executeStepWithRetry(step, previousResponses, stepExecution);
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
                        assertionResults = assertionEngine.evaluate(assertions, statusCode, responseBody, previousResponses);

                        boolean assertionsPassed = assertionResults.stream().allMatch(AssertionResult::isPassed);
                        if (!assertionsPassed) {
                            success = false;
                            String failedAssertions = assertionResults.stream()
                                    .filter(a -> !a.isPassed())
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