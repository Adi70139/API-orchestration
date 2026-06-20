package com.example.flowengine.service;

import com.example.flowengine.DTO.DuplicateFlowRequest;
import com.example.flowengine.DTO.FlowDTO;
import com.example.flowengine.DTO.FlowDetailedDTO;
import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.DTO.FlowStepRequest;
import com.example.flowengine.entity.Environment;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlowService {

    private final FlowRepository flowRepository;
    private final ModuleRepository moduleRepository;
    private final FlowExecutionRepository flowExecutionRepository;
    private final EnvironmentRepository environmentRepository;
    private final FlowStepRepository flowStepRepository;
    private final ObjectMapper objectMapper;

    @CacheEvict(cacheNames = {
            "flowsAll", "flowsByModuleName", "flowDetails", "stepsByFlow",
            "environmentsByModule", "decryptedEnvironmentVariables"
    }, allEntries = true)
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
        if (request.getFlowType() != null) flow.setFlowType(request.getFlowType());
        if (request.getPlaywrightScript() != null) flow.setPlaywrightScript(request.getPlaywrightScript());

        log.info("[FlowService] Creating flow name='{}' type='{}' hasScript={}",
                flow.getName(), flow.getFlowType(), flow.getPlaywrightScript() != null);

        flow = flowRepository.save(flow);
        return mapToFlowDTO(flow);
    }

    @CacheEvict(cacheNames = {
            "flowsAll", "flowsByModuleName", "flowDetails", "stepsByFlow",
            "environmentsByModule", "decryptedEnvironmentVariables"
    }, allEntries = true)
    public FlowDTO update(FlowRequest request, Long flowId) {

        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Flow not found with id: " + flowId));

        ModuleEntity module = moduleRepository.findByName(request.getModule())
                .orElseThrow(() ->
                        new IllegalArgumentException("Module not found with name: " + request.getModule()));

        Optional<FlowDefinition> duplicateFlow =
                flowRepository.findByNameAndModule_Id(
                        request.getName(),
                        module.getId()
                );

        if (duplicateFlow.isPresent()
                && !duplicateFlow.get().getId().equals(flowId)) {

            throw new IllegalArgumentException(
                    "Flow already exists with same name in module"
            );
        }

        log.info("Updating flow {} — name: '{}', desc: '{}'", flowId, request.getName(), request.getDescription());

        flow.setName(request.getName());
        flow.setDescription(request.getDescription());

        FlowDefinition updatedFlow = flowRepository.save(flow);

        return mapToFlowDTO(updatedFlow);
    }



    @Cacheable(cacheNames = "flowsByModuleName", key = "#moduleName")
    @Transactional(readOnly = true)
    public List<FlowDTO> getFlowsByModuleName(String moduleName) {
        log.info("Getting flows by module name: '{}'", moduleName);
        return flowRepository.findByModule_Name(moduleName).stream()
                .map(this::mapToFlowDTO)
                .collect(Collectors.toList());
    }

    @Cacheable(cacheNames = "flowsAll")
    @Transactional(readOnly = true)
    public List<FlowDTO> getAll() {
        log.info("Getting all flows from DB");
        return flowRepository.findAll().stream()
                .map(this::mapToFlowDTO)
                .collect(Collectors.toList());
    }

    @Cacheable(cacheNames = "flowDetails", key = "#id")
    @Transactional(readOnly = true)
    public FlowDetailedDTO getById(Long id) {
        log.info("Getting flow by id: {}", id);
        FlowDefinition flow = flowRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found with id: " + id));
        return mapToFlowDetailedDTO(flow);
    }

    @CacheEvict(cacheNames = {
            "flowsAll", "flowsByModuleName", "flowDetails", "stepsByFlow",
            "environmentsByModule", "decryptedEnvironmentVariables"
    }, allEntries = true)
    public void delete(Long flowId) {
        if (!flowRepository.existsById(flowId)) {
            throw new IllegalArgumentException("Flow not found with id: " + flowId);
        }
        // Clean up all execution records before deleting the flow
        flowExecutionRepository.findByFlowIdOrderByStartedAtDesc(flowId)
                .forEach(flowExecutionRepository::delete);

        flowRepository.deleteById(flowId);
    }

    @CacheEvict(cacheNames = {
            "flowsAll", "flowsByModuleName", "flowDetails", "stepsByFlow",
            "environmentsByModule", "decryptedEnvironmentVariables"
    }, allEntries = true)
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

    @CacheEvict(cacheNames = {
            "flowsAll", "flowsByModuleName", "flowDetails", "stepsByFlow",
            "environmentsByModule", "decryptedEnvironmentVariables"
    }, allEntries = true)
    public FlowDTO clearDefaultEnvironment(Long flowId) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
        flow.setDefaultEnvironment(null);
        flow = flowRepository.save(flow);
        return mapToFlowDTO(flow);
    }

    @CacheEvict(cacheNames = {
            "flowsAll", "flowsByModuleName", "flowDetails", "stepsByFlow",
            "environmentsByModule", "decryptedEnvironmentVariables"
    }, allEntries = true)
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
        Map<Long, Long> duplicatedStepIds = new HashMap<>();
        for (FlowStep originalStep : originalSteps) {
            FlowStep newStep = new FlowStep();
            newStep.setFlow(duplicate);
            newStep.setName(originalStep.getName());
            newStep.setStepOrder(originalStep.getStepOrder());
            newStep.setMethod(originalStep.getMethod());
            newStep.setUrl(originalStep.getUrl());
            newStep.setHeadersJson(originalStep.getHeadersJson());
            newStep.setBodyJson(originalStep.getBodyJson());
            if (originalStep.getBodySourceStepId() != null) {
                newStep.setBodySourceStepId(duplicatedStepIds.get(originalStep.getBodySourceStepId()));
            }
            //  newStep.setRequiredParams(originalStep.getRequiredParams());
            newStep.setAssertionsJson(originalStep.getAssertionsJson());
            newStep = flowStepRepository.save(newStep);
            duplicatedStepIds.put(originalStep.getId(), newStep.getId());
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
        dto.setFlowType(flow.getFlowType() != null ? flow.getFlowType() : "API");
        dto.setPlaywrightScript(flow.getPlaywrightScript());
        return dto;
    }

    private FlowDetailedDTO mapToFlowDetailedDTO(FlowDefinition flow) {
        FlowDetailedDTO dto = new FlowDetailedDTO();
        dto.setId(flow.getId());
        dto.setName(flow.getName());
        dto.setDescription(flow.getDescription());
        dto.setFlowType(flow.getFlowType() != null ? flow.getFlowType() : "API");
        dto.setPlaywrightScript(flow.getPlaywrightScript());
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
                        stepDTO.setHeadersJson(step.getHeadersJson());
                        stepDTO.setBodyJson(step.getBodyJson());
                        stepDTO.setBodySourceStepId(step.getBodySourceStepId());

                        // Deserialize payloadVariantsJson into structured list
                        if (step.getPayloadVariantsJson() != null && !step.getPayloadVariantsJson().isBlank()) {
                            try {
                                stepDTO.setPayloadVariants(objectMapper.readValue(
                                        step.getPayloadVariantsJson(),
                                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<FlowStepRequest.PayloadVariant>>() {}));
                            } catch (Exception e) {
                                log.warn("Failed to parse payloadVariantsJson for step {}: {}", step.getId(), e.getMessage());
                            }
                        }

                        // Deserialize assertionsJson into structured object
                        if (step.getAssertionsJson() != null) {
                            try {
                                FlowDetailedDTO.AssertionsDTO assertions = objectMapper.readValue(
                                        step.getAssertionsJson(),
                                        FlowDetailedDTO.AssertionsDTO.class
                                );
                                stepDTO.setAssertions(assertions);
                            } catch (Exception e) {
                                // malformed json — skip silently
                            }
                        }

                        // Deserialize skipConditionJson
                        if (step.getSkipConditionJson() != null) {
                            try {
                                FlowStepRequest.SkipConditionRequest skipCondition = objectMapper.readValue(
                                        step.getSkipConditionJson(),
                                        FlowStepRequest.SkipConditionRequest.class
                                );
                                stepDTO.setSkipCondition(skipCondition);
                            } catch (Exception e) {
                                // malformed json — skip silently
                            }
                        }
                        return stepDTO;
                    })
                    .collect(Collectors.toList()));
        }
        return dto;
    }
}