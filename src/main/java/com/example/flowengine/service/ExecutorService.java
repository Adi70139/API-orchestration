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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    @Transactional
    public ModuleExecutionResult runModule(Long moduleId) {
        ModuleEntity module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleId));

        List<FlowDefinition> flows = flowRepository.findByModuleId(moduleId);

        // Create module execution record
        ModuleExecution moduleExecution = new ModuleExecution();
        moduleExecution.setModule(module);
        moduleExecution.setStartedAt(LocalDateTime.now());
        moduleExecution.setStatus(ExecutionStatus.IN_PROGRESS);
        moduleExecution = moduleExecutionRepository.save(moduleExecution);

        List<FlowExecutionResult> flowResults = new ArrayList<>();
        long moduleStart = System.currentTimeMillis();
        boolean allPassed = true;

        for (FlowDefinition flow : flows) {
            FlowExecutionResult flowResult = executeFlow(flow, moduleExecution);
            flowResults.add(flowResult);
            if (!flowResult.isAllStepsPassed()) allPassed = false;
        }

        // Update module execution record
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

    @Transactional
    public FlowExecutionResult runFlow(Long flowId) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        return executeFlow(flow, null);
    }

    private FlowExecutionResult executeFlow(FlowDefinition flow, ModuleExecution moduleExecution) {
        List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flow.getId());

        // Delete previous execution for this flow (keep only latest)
        flowExecutionRepository.findByFlowId(flow.getId())
                .ifPresent(flowExecutionRepository::delete);

        FlowExecution flowExecution = new FlowExecution();
        flowExecution.setFlow(flow);
        flowExecution.setModuleExecution(moduleExecution);
        flowExecution.setStartedAt(LocalDateTime.now());
        flowExecution.setStatus(ExecutionStatus.IN_PROGRESS);
        flowExecution = flowExecutionRepository.save(flowExecution);

        List<StepExecutionResult> stepResults = new ArrayList<>();
        List<String> previousResponses = new ArrayList<>(); // response chain for placeholder resolution
        long flowStart = System.currentTimeMillis();
        boolean allPassed = true;

        for (FlowStep step : steps) {
            StepExecution stepExecution = new StepExecution();
            stepExecution.setFlowExecution(flowExecution);
            stepExecution.setStepId(step.getId());
            stepExecution.setStepName(step.getName());
            stepExecution.setStepOrder(step.getStepOrder());

            StepExecutionResult stepResult = executeStep(step, previousResponses, stepExecution);
            stepResults.add(stepResult);

            if (stepResult.isSuccess()) {
                // Add this step's response to the chain for subsequent steps
                previousResponses.add(stepResult.getResponseBody());
            } else {
                allPassed = false;
                log.warn("Step '{}' failed in flow '{}': {}", step.getName(), flow.getName(), stepResult.getErrorMessage());
                break; // fail fast
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


               // Evaluate assertions if defined
                List<AssertionResult> assertionResults = new ArrayList<>();
                if (step.getAssertionsJson() != null) {
                    try {
                        FlowStepRequest.AssertionsRequest assertions = objectMapper.readValue(
                                step.getAssertionsJson(), FlowStepRequest.AssertionsRequest.class);
                        assertionResults = assertionEngine.evaluate(assertions, statusCode, responseBody);

                        // If any assertion failed, mark step as failed
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

                // Serialize assertion results to DB
                if (!assertionResults.isEmpty()) {
                    try {
                        stepExecution.setAssertionResultsJson(objectMapper.writeValueAsString(assertionResults));
                    } catch (Exception ignored) {}
                }

                stepExecution.setSuccess(success);
                stepExecution.setStatusCode(statusCode);
                stepExecution.setResponseBody(responseBody);
                stepExecution.setDurationMs(duration);
                stepExecutionRepository.save(stepExecution);

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
                result.setErrorMessage(success ? null : "HTTP " + statusCode);
                result.setDurationMs(duration);
                result.setAssertionResults(assertionResults.isEmpty() ? null : assertionResults);
                result.setSuccess(success);
                result.setStatusCode(statusCode);
                result.setResponseBody(responseBody);
                result.setDurationMs(duration);
                result.setErrorMessage(stepExecution.getErrorMessage());
                return result;
            }

        } catch (IllegalArgumentException e) {
            long duration = System.currentTimeMillis() - start;
            stepExecution.setSuccess(false);
            stepExecution.setErrorMessage(e.getMessage());
            stepExecution.setDurationMs(duration);
            stepExecutionRepository.save(stepExecution); // <-- SAVE even on failure

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
            stepExecutionRepository.save(stepExecution); // <-- SAVE even on exception

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