package com.example.flowengine.service;

import com.example.flowengine.DTO.FlowExecutionResult;
import com.example.flowengine.DTO.ModuleExecutionResult;
import com.example.flowengine.DTO.StepExecutionResult;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.repository.FlowRepository;
import com.example.flowengine.repository.FlowStepRepository;
import com.example.flowengine.repository.ModuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * Run all flows in a module sequentially.
     */
    public ModuleExecutionResult runModule(Long moduleId) {
        ModuleEntity module = moduleRepository.findById(moduleId)
            .orElseThrow(() -> new IllegalArgumentException("Module not found with id: " + moduleId));

        List<FlowDefinition> flows = flowRepository.findByModuleId(moduleId);

        ModuleExecutionResult result = new ModuleExecutionResult();
        result.setModuleId(moduleId);
        result.setModuleName(module.getName());

        List<FlowExecutionResult> flowResults = new ArrayList<>();
        long moduleStart = System.currentTimeMillis();
        boolean allPassed = true;

        for (FlowDefinition flow : flows) {
            FlowExecutionResult flowResult = executeFlow(flow);
            flowResults.add(flowResult);
            if (!flowResult.isAllStepsPassed()) {
                allPassed = false;
            }
        }

        result.setFlowResults(flowResults);
        result.setAllFlowsPassed(allPassed);
        result.setTotalDurationMs(System.currentTimeMillis() - moduleStart);

        return result;
    }

    /**
     * Run a single flow by ID.
     */
    public FlowExecutionResult runFlow(Long flowId) {
        FlowDefinition flow = flowRepository.findById(flowId)
            .orElseThrow(() -> new IllegalArgumentException("Flow not found with id: " + flowId));

        return executeFlow(flow);
    }

    private FlowExecutionResult executeFlow(FlowDefinition flow) {
        List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flow.getId());

        FlowExecutionResult result = new FlowExecutionResult();
        result.setFlowId(flow.getId());
        result.setFlowName(flow.getName());

        List<StepExecutionResult> stepResults = new ArrayList<>();
        long flowStart = System.currentTimeMillis();
        boolean allPassed = true;

        for (FlowStep step : steps) {
            StepExecutionResult stepResult = executeStep(step);
            stepResults.add(stepResult);
            if (!stepResult.isSuccess()) {
                allPassed = false;
                log.warn("Step '{}' (id={}) failed in flow '{}'. Continuing remaining steps.",
                    step.getName(), step.getId(), flow.getName());
            }
        }

        result.setStepResults(stepResults);
        result.setAllStepsPassed(allPassed);
        result.setTotalDurationMs(System.currentTimeMillis() - flowStart);

        return result;
    }

    @SuppressWarnings("unchecked")
    private StepExecutionResult executeStep(FlowStep step) {
        long start = System.currentTimeMillis();

        try {
            Request.Builder requestBuilder = new Request.Builder().url(step.getUrl());

            // Add headers if provided
            if (step.getHeadersJson() != null && !step.getHeadersJson().isBlank()) {
                Map<String, String> headers = objectMapper.readValue(step.getHeadersJson(), Map.class);
                headers.forEach(requestBuilder::addHeader);
            }

            // Build request body for methods that support it
            String method = step.getMethod().toUpperCase();
            RequestBody body = null;
            if (requiresBody(method)) {
                String bodyContent = step.getBodyJson() != null ? step.getBodyJson() : "{}";
                body = RequestBody.create(bodyContent, JSON_MEDIA_TYPE);
            }

            requestBuilder.method(method, body);

            try (Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
                long duration = System.currentTimeMillis() - start;
                String responseBody = response.body() != null ? response.body().string() : "";
                boolean success = response.isSuccessful();

                return new StepExecutionResult(
                    step.getId(),
                    step.getName(),
                    step.getStepOrder(),
                    response.code(),
                    responseBody,
                    success,
                    success ? null : "HTTP " + response.code(),
                    duration
                );
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Exception executing step '{}': {}", step.getName(), e.getMessage());
            return new StepExecutionResult(
                step.getId(),
                step.getName(),
                step.getStepOrder(),
                0,
                null,
                false,
                e.getMessage(),
                duration
            );
        }
    }

    private boolean requiresBody(String method) {
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
    }
}
