package com.example.flowengine.service;

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

    private final FlowStepRepository flowStepRepository;
    private final FlowRepository flowRepository;
    private final ObjectMapper objectMapper;

    public FlowStep create(Long flowId, FlowStepRequest request) {
        FlowDefinition flow = flowRepository.findById(flowId)
            .orElseThrow(() -> new IllegalArgumentException("Flow not found with id: " + flowId));

        FlowStep step = new FlowStep();
        step.setFlow(flow);
        step.setName(request.getName());
        step.setStepOrder(request.getStepOrder());
        step.setDescription(request.getDescription());
        step.setMethod(request.getMethod().toUpperCase());
        step.setUrl(request.getUrl());
        step.setHeadersJson(request.getHeadersJson());
        step.setBodyJson(request.getBodyJson());

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
        step.setStepOrder(request.getStepOrder());
        step.setDescription(request.getDescription());
        step.setMethod(request.getMethod().toUpperCase());
        step.setUrl(request.getUrl());
        step.setHeadersJson(request.getHeadersJson());
        step.setBodyJson(request.getBodyJson());

        mapAssertions(step, request);
        return flowStepRepository.save(step);
    }

    public void delete(Long stepId) {
        if (!flowStepRepository.existsById(stepId)) {
            throw new IllegalArgumentException("FlowStep not found with id: " + stepId);
        }
        flowStepRepository.deleteById(stepId);
    }

    // In create() and update(), after setting other fields:
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
