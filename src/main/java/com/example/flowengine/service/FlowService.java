package com.example.flowengine.service;

import com.example.flowengine.DTO.DuplicateFlowRequest;
import com.example.flowengine.DTO.FlowDTO;
import com.example.flowengine.DTO.FlowDetailedDTO;
import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.entity.Environment;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FlowService {

    private final FlowRepository flowRepository;
    private final ModuleRepository moduleRepository;
    private final FlowExecutionRepository flowExecutionRepository;
    private final EnvironmentRepository environmentRepository;
    private final FlowStepRepository flowStepRepository;

    public FlowDTO create(FlowRequest request) {
        // Find the module by name
        ModuleEntity module = moduleRepository.findByName(request.getModule())
            .orElseThrow(() -> new IllegalArgumentException("Module not found with name: " + request.getModule()));

        // Check for duplicate flows within the same module
        Optional<FlowDefinition> existingFlow = flowRepository.findByNameAndModule_Id(request.getName(), module.getId());
        if (existingFlow.isPresent()) {
            throw new IllegalArgumentException("Flow with name '" + request.getName() + 
                "' already exists in module '" + module.getName() + "'");
        }

        // Create new FlowDefinition
        FlowDefinition flow = new FlowDefinition();
        flow.setName(request.getName());
        flow.setDescription(request.getDescription());
        flow.setModule(module);

        flow = flowRepository.save(flow);
        return mapToFlowDTO(flow);
    }

    public List<FlowDTO> getFlowsByModuleName(String moduleName) {
        return flowRepository.findByModule_Name(moduleName).stream()
                .map(this::mapToFlowDTO)
                .collect(Collectors.toList());
    }

    public List<FlowDTO> getAll() {
        return flowRepository.findAll().stream()
                .map(this::mapToFlowDTO)
                .collect(Collectors.toList());
    }

    public FlowDetailedDTO getById(Long id) {
        FlowDefinition flow = flowRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Flow not found with id: " + id));
        return mapToFlowDetailedDTO(flow);
    }

    public void delete(Long flowId) {
        if (!flowRepository.existsById(flowId)) {
            throw new IllegalArgumentException("Flow not found with id: " + flowId);
        }
        // Clean up execution records first
        flowExecutionRepository.findByFlowId(flowId)
                .ifPresent(flowExecutionRepository::delete);

        flowRepository.deleteById(flowId);
    }

    public FlowDTO setDefaultEnvironment(Long flowId, Long environmentId) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        Environment env = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + environmentId));

        // Ensure env belongs to the same module as the flow
        if (!env.getModule().getId().equals(flow.getModule().getId())) {
            throw new IllegalArgumentException(
                    "Environment does not belong to the same module as this flow");
        }

        flow.setDefaultEnvironment(env);
        flow = flowRepository.save(flow);
        return mapToFlowDTO(flow);
    }

    public FlowDTO clearDefaultEnvironment(Long flowId) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
        flow.setDefaultEnvironment(null);
        flow = flowRepository.save(flow);
        return mapToFlowDTO(flow);
    }

    public FlowDTO duplicateFlow(Long flowId, DuplicateFlowRequest request) {
        FlowDefinition original = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        // Resolve target module
        ModuleEntity targetModule;
        if (request.getTargetModuleId() != null) {
            targetModule = moduleRepository.findById(request.getTargetModuleId())
                    .orElseThrow(() -> new IllegalArgumentException("Target module not found: " + request.getTargetModuleId()));
        } else {
            targetModule = original.getModule();
        }

        // Resolve new name
        String newName = (request.getName() != null && !request.getName().isBlank())
                ? request.getName()
                : "Copy of " + original.getName();

        // Create new flow
        FlowDefinition duplicate = new FlowDefinition();
        duplicate.setName(newName);
        duplicate.setDescription(original.getDescription());
        duplicate.setModule(targetModule);
        duplicate.setDefaultEnvironment(original.getDefaultEnvironment());
        duplicate = flowRepository.save(duplicate);

        // Copy all steps
        List<FlowStep> originalSteps = flowStepRepository.findByFlowIdOrderByStepOrder(original.getId());
        for (FlowStep originalStep : originalSteps) {
            FlowStep newStep = new FlowStep();
            newStep.setFlow(duplicate);
            newStep.setName(originalStep.getName());
            newStep.setStepOrder(originalStep.getStepOrder());
            newStep.setMethod(originalStep.getMethod());
            newStep.setUrl(originalStep.getUrl());
            newStep.setHeadersJson(originalStep.getHeadersJson());
            newStep.setBodyJson(originalStep.getBodyJson());
          //  newStep.setRequiredParams(originalStep.getRequiredParams());
            newStep.setAssertionsJson(originalStep.getAssertionsJson());
            flowStepRepository.save(newStep);
        }

        FlowDefinition saved = flowRepository.findById(duplicate.getId()).orElseThrow();
        return mapToFlowDTO(saved);
    }

    // ─── Mapping Methods ─────────────────────────────────────────────────────

    private FlowDTO mapToFlowDTO(FlowDefinition flow) {
        FlowDTO dto = new FlowDTO();
        dto.setId(flow.getId());
        dto.setName(flow.getName());
        dto.setDescription(flow.getDescription());
        if (flow.getModule() != null) {
            dto.setModuleId(flow.getModule().getId());
            dto.setModuleName(flow.getModule().getName());
        }
        if (flow.getDefaultEnvironment() != null) {
            dto.setDefaultEnvironmentId(flow.getDefaultEnvironment().getId());
        }
        dto.setStepCount(flow.getSteps() != null ? flow.getSteps().size() : 0);
        return dto;
    }

    private FlowDetailedDTO mapToFlowDetailedDTO(FlowDefinition flow) {
        FlowDetailedDTO dto = new FlowDetailedDTO();
        dto.setId(flow.getId());
        dto.setName(flow.getName());
        dto.setDescription(flow.getDescription());
        if (flow.getModule() != null) {
            dto.setModuleId(flow.getModule().getId());
            dto.setModuleName(flow.getModule().getName());
        }
        if (flow.getDefaultEnvironment() != null) {
            dto.setDefaultEnvironmentId(flow.getDefaultEnvironment().getId());
        }
        if (flow.getSteps() != null) {
            dto.setSteps(flow.getSteps().stream()
                    .map(step -> {
                        FlowDetailedDTO.FlowStepDetailDTO stepDTO = new FlowDetailedDTO.FlowStepDetailDTO();
                        stepDTO.setId(step.getId());
                        stepDTO.setName(step.getName());
                        stepDTO.setDescription(step.getDescription());
                        stepDTO.setStepOrder(step.getStepOrder());
                        stepDTO.setMethod(step.getMethod());
                        stepDTO.setUrl(step.getUrl());
                        return stepDTO;
                    })
                    .collect(Collectors.toList()));
        }
        return dto;
    }
}
