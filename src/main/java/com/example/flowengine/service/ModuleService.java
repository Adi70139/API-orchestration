package com.example.flowengine.service;

import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.DTO.ModuleResponse;
import com.example.flowengine.DTO.ModuleUpdateRequest;
import com.example.flowengine.entity.Environment;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.entity.ModuleExecution;
import com.example.flowengine.repository.*;
import com.example.flowengine.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ModuleService {

    private final ModuleRepository repository;
    private final FlowExecutionRepository flowExecutionRepository;
    private final FlowRepository flowRepository;
    private final ModuleScheduleRepository moduleScheduleRepository;
    private final ModuleExecutionRepository moduleExecutionRepository;
    private final EnvironmentRepository environmentRepository;

    @CacheEvict(cacheNames = {
            "flowsAll", "flowsByModuleName", "flowDetails", "stepsByFlow",
            "environmentsByModule", "environmentsById", "decryptedEnvironmentVariables"
    }, allEntries = true)
    public ModuleEntity create(ModuleEntity module) {
        // Stamp ownership — admin-created modules with no explicit owner stay unowned (visible to all)
        SecurityUtils.currentUser().ifPresent(user -> {
            if (module.getCreatedBy() == null) {
                module.setCreatedBy(user);
            }
        });
        return repository.save(module);
    }

    @CacheEvict(cacheNames = {
            "flowsAll", "flowsByModuleName", "flowDetails", "stepsByFlow",
            "environmentsByModule", "environmentsById", "decryptedEnvironmentVariables"
    }, allEntries = true)
    public ModuleResponse update(ModuleUpdateRequest module, Long moduleId) {

        ModuleEntity existingModule = repository.findById(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Module not found with id: " + moduleId));

        existingModule.setName(module.getName());
        existingModule.setDescription(module.getDescription());
        repository.save(existingModule);

        ModuleResponse dto = new ModuleResponse();
        dto.setId(existingModule.getId());
        dto.setName(existingModule.getName());
        dto.setDescription(existingModule.getDescription());
        dto.setFlowCount(
                existingModule.getFlows() != null
                        ? existingModule.getFlows().size()
                        : 0
        );

        return dto;
    }

    public List<ModuleResponse> getAll() {
        var currentUser = SecurityUtils.currentUser();
        return repository.findAll()
                .stream()
                // ADMIN sees everything.
                // USER sees: modules they own OR modules with no owner (legacy/shared).
                .filter(module -> {
                    if (currentUser.isEmpty() || SecurityUtils.isAdmin()) return true;
                    Long userId = currentUser.get().getId();
                    return module.getCreatedBy() == null
                            || module.getCreatedBy().getId().equals(userId);
                })
                .map(module -> {
                    ModuleResponse dto = new ModuleResponse();
                    dto.setId(module.getId());
                    dto.setName(module.getName());
                    dto.setDescription(module.getDescription());
                    dto.setFlowCount(
                            module.getFlows() != null
                                    ? module.getFlows().size()
                                    : 0
                    );
                    return dto;
                })
                .toList();
    }

    @CacheEvict(cacheNames = {
            "flowsAll", "flowsByModuleName", "flowDetails", "stepsByFlow", "stepsById",
            "environmentsByModule", "environmentsById", "decryptedEnvironmentVariables"
    }, allEntries = true)
    public void delete(Long moduleId) {
        if (!repository.existsById(moduleId)) {
            throw new IllegalArgumentException("Module not found with id: " + moduleId);
        }

        moduleScheduleRepository.findByModuleId(moduleId)
                .ifPresent(moduleScheduleRepository::delete);

        // Clear environment references from all flows to avoid FK constraint violation
        List<FlowDefinition> flows = flowRepository.findByModuleId(moduleId);
        for (FlowDefinition flow : flows) {
            flow.setDefaultEnvironment(null);
            flowRepository.save(flow);
        }

        // Delete environments after clearing references
        List<Environment> environments = environmentRepository.findByModuleId(moduleId);
        environmentRepository.deleteAll(environments);

        // Delete all flow executions (and their step executions via cascade) for all flows
        for (FlowDefinition flow : flows) {
            flowExecutionRepository.deleteAll(
                    flowExecutionRepository.findByFlowIdOrderByStartedAtDesc(flow.getId())
            );
        }

        repository.deleteById(moduleId);
    }
}
