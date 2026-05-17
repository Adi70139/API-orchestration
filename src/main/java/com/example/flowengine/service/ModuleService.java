
package com.example.flowengine.service;

import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.DTO.ModuleResponse;
import com.example.flowengine.DTO.ModuleUpdateRequest;
import com.example.flowengine.entity.Environment;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.entity.ModuleExecution;
import com.example.flowengine.repository.*;
import lombok.RequiredArgsConstructor;
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

    public ModuleEntity create(ModuleEntity module) {
        return repository.save(module);
    }

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

        return repository.findAll()
                .stream()
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

    public void delete(Long moduleId) {
        if (!repository.existsById(moduleId)) {
            throw new IllegalArgumentException("Module not found with id: " + moduleId);
        }

        moduleScheduleRepository.findByModuleId(moduleId)
                .ifPresent(moduleScheduleRepository::delete);

        // Clear environment references from all flows in this module to avoid FK constraint violation
        List<FlowDefinition> flows = flowRepository.findByModuleId(moduleId);
        for (FlowDefinition flow : flows) {
            flow.setDefaultEnvironment(null);
            flowRepository.save(flow);
        }

        // Delete environments after clearing references
        List<Environment> environments = environmentRepository.findByModuleId(moduleId);
        environmentRepository.deleteAll(environments);

        // Delete flow executions (and their step executions via cascade) for all flows in this module
        for (FlowDefinition flow : flows) {
            flowExecutionRepository.findByFlowId(flow.getId())
                    .ifPresent(fe -> flowExecutionRepository.delete(fe));
        }

        repository.deleteById(moduleId);
    }
}
