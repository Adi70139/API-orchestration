
package com.example.flowengine.service;

import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.DTO.ModuleResponse;
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

    public ModuleEntity create(ModuleEntity module) {
        return repository.save(module);
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

        // Delete flow executions (and their step executions via cascade) for all flows in this module
        List<FlowDefinition> flows = flowRepository.findByModuleId(moduleId);
        for (FlowDefinition flow : flows) {
            flowExecutionRepository.findByFlowId(flow.getId())
                    .ifPresent(fe -> flowExecutionRepository.delete(fe));
        }

        moduleScheduleRepository.findByModuleId(moduleId)
                .ifPresent(moduleScheduleRepository::delete);

        repository.deleteById(moduleId);
    }
}
