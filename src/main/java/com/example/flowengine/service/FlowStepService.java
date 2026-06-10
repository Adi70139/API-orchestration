package com.example.flowengine.service;

import com.example.flowengine.DTO.DuplicateFlowStepRequest;
import com.example.flowengine.DTO.FlowStepRequest;
import com.example.flowengine.DTO.FlowStepReorderRequest;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.repository.FlowRepository;
import com.example.flowengine.repository.FlowStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FlowStepService {

    private static final int MAX_RETRY_COUNT = 5;
    private static final int MAX_RETRY_DELAY_MS = 10_000;
    private static final int MAX_INITIAL_DELAY_MS = 30_000;
    private static final int MAX_POLL_INTERVAL_MS = 30_000;
    private static final int MAX_POLL_ATTEMPTS = 50;

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
        mapBodySource(step, request, flowId, maxOrder + 1);
        mapRetryConfig(step, request);
        mapAssertions(step, request);
        mapSkipCondition(step, request);
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
        mapBodySource(step, request, step.getFlow().getId(), step.getStepOrder());
        mapRetryConfig(step, request);
        mapAssertions(step, request);
        mapSkipCondition(step, request);
        return flowStepRepository.save(step);
    }

    @Transactional
    public List<FlowStep> reorder(Long flowId, FlowStepReorderRequest request) {
        if (!flowRepository.existsById(flowId)) {
            throw new IllegalArgumentException("Flow not found with id: " + flowId);
        }

        List<FlowStep> existingSteps = flowStepRepository.findByFlowIdOrderByStepOrder(flowId);
        Map<Long, FlowStep> stepsById = existingSteps.stream()
                .collect(Collectors.toMap(FlowStep::getId, Function.identity()));
        Set<Long> requestedStepIds = new HashSet<>();
        Set<Integer> requestedOrders = new HashSet<>();

        for (FlowStepReorderRequest.StepOrderUpdate update : request.getSteps()) {
            if (!requestedStepIds.add(update.getStepId())) {
                throw new IllegalArgumentException("Duplicate step id in reorder request: " + update.getStepId());
            }
            if (!requestedOrders.add(update.getStepOrder())) {
                throw new IllegalArgumentException("Duplicate step order in reorder request: " + update.getStepOrder());
            }
            FlowStep step = stepsById.get(update.getStepId());
            if (step == null) {
                throw new IllegalArgumentException("FlowStep with id " + update.getStepId() + " does not belong to flow: " + flowId);
            }
            step.setStepOrder(update.getStepOrder());
        }

        if (requestedStepIds.size() != existingSteps.size() || !requestedStepIds.containsAll(stepsById.keySet())) {
            throw new IllegalArgumentException("Reorder request must include every step in the flow exactly once");
        }

        Set<Integer> finalOrders = new HashSet<>();
        for (FlowStep step : existingSteps) {
            if (!finalOrders.add(step.getStepOrder())) {
                throw new IllegalArgumentException("Duplicate step order after reorder: " + step.getStepOrder());
            }
            if (step.getBodySourceStepId() != null) {
                FlowStep source = stepsById.get(step.getBodySourceStepId());
                if (source == null || source.getStepOrder() >= step.getStepOrder()) {
                    throw new IllegalArgumentException(
                            "Step " + step.getId() + " must remain after its body source step "
                                    + step.getBodySourceStepId());
                }
            }
        }

        flowStepRepository.saveAll(existingSteps);
        return flowStepRepository.findByFlowIdOrderByStepOrder(flowId);
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
        duplicate.setBodySourceStepId(original.getBodySourceStepId());
        duplicate.setAssertionsJson(original.getAssertionsJson());
        duplicate.setRetryCount(original.getRetryCount());
        duplicate.setRetryDelayMs(original.getRetryDelayMs());
        duplicate.setInitialDelayMs(original.getInitialDelayMs());
        duplicate.setSkipConditionJson(original.getSkipConditionJson());

        return flowStepRepository.save(duplicate);
    }

    public void delete(Long stepId) {
        FlowStep step = getById(stepId);
        List<Long> dependentStepIds = flowStepRepository.findByFlowIdOrderByStepOrder(step.getFlow().getId()).stream()
                .filter(candidate -> stepId.equals(candidate.getBodySourceStepId()))
                .map(FlowStep::getId)
                .toList();
        if (!dependentStepIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot delete step " + stepId + " because it provides the request body for steps: "
                            + dependentStepIds);
        }
        flowStepRepository.deleteById(stepId);
    }

    private void mapBodySource(FlowStep step, FlowStepRequest request, Long flowId, Integer stepOrder) {
        if (!Boolean.TRUE.equals(request.getInheritBodyFromPreviousStep())) {
            step.setBodySourceStepId(null);
            return;
        }

        Long sourceStepId = request.getBodySourceStepId();
        if (sourceStepId == null) {
            sourceStepId = flowStepRepository.findByFlowIdOrderByStepOrder(flowId).stream()
                    .filter(candidate -> candidate.getStepOrder() < stepOrder)
                    .max(java.util.Comparator.comparing(FlowStep::getStepOrder))
                    .map(FlowStep::getId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Body inheritance requires a previous step"));
        }

        FlowStep sourceStep = getById(sourceStepId);
        if (!sourceStep.getFlow().getId().equals(flowId)) {
            throw new IllegalArgumentException("Body source step does not belong to flow: " + flowId);
        }
        if (sourceStep.getStepOrder() >= stepOrder) {
            throw new IllegalArgumentException("Body source must be a previous step in the flow");
        }
        step.setBodySourceStepId(sourceStepId);
    }

    private void mapRetryConfig(FlowStep step, FlowStepRequest request) {
        int retryCount = request.getRetryCount() != null ? request.getRetryCount() : 0;
        int retryDelayMs = request.getRetryDelayMs() != null ? request.getRetryDelayMs() : 1000;
        int initialDelayMs = request.getInitialDelayMs() != null ? request.getInitialDelayMs() : 0;

        // Enforce bounds — never trust user input on these
        step.setRetryCount(Math.min(Math.max(retryCount, 0), MAX_RETRY_COUNT));
        step.setRetryDelayMs(Math.min(Math.max(retryDelayMs, 0), MAX_RETRY_DELAY_MS));
        step.setInitialDelayMs(Math.min(Math.max(initialDelayMs, 0), MAX_INITIAL_DELAY_MS));

        // Polling config
        boolean pollUntilSuccess = Boolean.TRUE.equals(request.getPollUntilSuccess());
        int pollIntervalMs = request.getPollIntervalMs() != null ? request.getPollIntervalMs() : 5000;
        int pollMaxAttempts = request.getPollMaxAttempts() != null ? request.getPollMaxAttempts() : 10;
        int pollExpectedStatus = request.getPollExpectedStatus() != null ? request.getPollExpectedStatus() : 200;

        step.setPollUntilSuccess(pollUntilSuccess);
        step.setPollIntervalMs(Math.min(Math.max(pollIntervalMs, 500), MAX_POLL_INTERVAL_MS));
        step.setPollMaxAttempts(Math.min(Math.max(pollMaxAttempts, 1), MAX_POLL_ATTEMPTS));
        step.setPollExpectedStatus(pollExpectedStatus);
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

    private void mapSkipCondition(FlowStep step, FlowStepRequest request) {
        if (request.getSkipCondition() != null) {
            try {
                step.setSkipConditionJson(objectMapper.writeValueAsString(request.getSkipCondition()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid skipCondition format: " + e.getMessage());
            }
        } else {
            step.setSkipConditionJson(null);
        }
    }
}
