package com.example.flowengine.service;

import com.example.flowengine.DTO.FlowRequest;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.repository.FlowRepository;
import com.example.flowengine.repository.ModuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FlowService {

    private final FlowRepository flowRepository;
    private final ModuleRepository moduleRepository;

    public FlowDefinition create(FlowRequest request) {
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

        return flowRepository.save(flow);
    }

    public List<FlowDefinition> getFlowsByModuleName(String moduleName) {
        return flowRepository.findByModule_Name(moduleName);
    }

    public List<FlowDefinition> getAll() {
        return flowRepository.findAll();
    }

    public FlowDefinition getById(Long id) {
        return flowRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Flow not found with id: " + id));
    }
}
