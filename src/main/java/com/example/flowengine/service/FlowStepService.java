package com.example.flowengine.service;

import com.example.flowengine.DTO.DuplicateFlowStepRequest;
import com.example.flowengine.DTO.FlowStepRequest;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.repository.FlowRepository;
import com.example.flowengine.repository.FlowStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FlowStepService {

    private static final int MAX_RETRY_COUNT = 5;
    private static final int MAX_RETRY_DELAY_MS = 10_000;
    private static final int MAX_INITIAL_DELAY_MS = 30_000;

    private final FlowStepRepository flowStepRepository;
    private final FlowRepository flowRepository;
    private final ObjectMapper objectMapper;

    public FlowStep create(Long flowId, FlowStepRequest request) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found with id: " + flowId));

        Integer maxOrder = flowStepRepository.findMaxStepOrderByFlowId(flowId);

        FlowStep step = new FlowStep();
        step.setFlow(flow);
        step.setName(request.getName());
        step.setStepOrder(maxOrder + 1);
        step.setDescription(request.getDescription());
        step.setMethod(request.getMethod().toUpperCase());
        step.setUrl(request.getUrl());
        step.setHeadersJson(request.getHeadersJson());
        step.setBodyJson(request.getBodyJson());
        mapRetryConfig(step, request);
        mapAssertions(step, request);
        return flowStepRepository.save(step);
    }

    public List<FlowStep> getByFlowId(Long flowId) {
        if (!flowRepository.existsById(flowId)) {
            throw new IllegalArgumentException("Flow not found with id: " + flowId);
        }
        return flowStepRepository.findByFlowIdOrderByStepOrder(flowId);
    }

    public FlowStep getById(Long stepId) {
        return flowStepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("FlowStep not found with id: " + stepId));
    }

    public FlowStep update(Long stepId, FlowStepRequest request) {
        FlowStep step = getById(stepId);
        step.setName(request.getName());
        step.setDescription(request.getDescription());
        step.setMethod(request.getMethod().toUpperCase());
        step.setUrl(request.getUrl());
        step.setHeadersJson(request.getHeadersJson());
        step.setBodyJson(request.getBodyJson());
        mapRetryConfig(step, request);
        mapAssertions(step, request);
        return flowStepRepository.save(step);
    }

    public FlowStep duplicate(Long flowId, Long stepId, DuplicateFlowStepRequest request) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found with id: " + flowId));

        FlowStep original = getById(stepId);
        if (!original.getFlow().getId().equals(flowId)) {
            throw new IllegalArgumentException("FlowStep with id " + stepId + " does not belong to flow: " + flowId);
        }

        Integer maxOrder = flowStepRepository.findMaxStepOrderByFlowId(flowId);
        String newName = (request != null && request.getName() != null && !request.getName().isBlank())
                ? request.getName()
                : "Copy of " + original.getName();

        FlowStep duplicate = new FlowStep();
        duplicate.setFlow(flow);
        duplicate.setName(newName);
        duplicate.setStepOrder(maxOrder + 1);
        duplicate.setDescription(original.getDescription());
        duplicate.setMethod(original.getMethod());
        duplicate.setUrl(original.getUrl());
        duplicate.setHeadersJson(original.getHeadersJson());
        duplicate.setBodyJson(original.getBodyJson());
        duplicate.setAssertionsJson(original.getAssertionsJson());
        duplicate.setRetryCount(original.getRetryCount());
        duplicate.setRetryDelayMs(original.getRetryDelayMs());
        duplicate.setInitialDelayMs(original.getInitialDelayMs());

        return flowStepRepository.save(duplicate);
    }

    public void delete(Long stepId) {
        if (!flowStepRepository.existsById(stepId)) {
            throw new IllegalArgumentException("FlowStep not found with id: " + stepId);
        }
        flowStepRepository.deleteById(stepId);
    }

    private void mapRetryConfig(FlowStep step, FlowStepRequest request) {
        int retryCount = request.getRetryCount() != null ? request.getRetryCount() : 0;
        int retryDelayMs = request.getRetryDelayMs() != null ? request.getRetryDelayMs() : 1000;
        int initialDelayMs = request.getInitialDelayMs() != null ? request.getInitialDelayMs() : 0;

        // Enforce bounds — never trust user input on these
        step.setRetryCount(Math.min(Math.max(retryCount, 0), MAX_RETRY_COUNT));
        step.setRetryDelayMs(Math.min(Math.max(retryDelayMs, 0), MAX_RETRY_DELAY_MS));
        step.setInitialDelayMs(Math.min(Math.max(initialDelayMs, 0), MAX_INITIAL_DELAY_MS));
    }

    private void mapAssertions(FlowStep step, FlowStepRequest request) {
        if (request.getAssertions() != null) {
            try {
                step.setAssertionsJson(objectMapper.writeValueAsString(request.getAssertions()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid assertions format: " + e.getMessage());
            }
        } else {
            step.setAssertionsJson(null);
        }
    }
}
