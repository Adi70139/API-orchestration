package com.example.flowengine.service;

import com.example.flowengine.DTO.EnvironmentRequest;
import com.example.flowengine.DTO.EnvironmentResponse;
import com.example.flowengine.entity.Environment;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.repository.EnvironmentRepository;
import com.example.flowengine.repository.ModuleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.repository.FlowRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;
    private final ModuleRepository moduleRepository;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final FlowRepository flowRepository;

    public EnvironmentResponse create(Long moduleId, EnvironmentRequest request) {
        ModuleEntity module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleId));

        if (environmentRepository.findByNameAndModuleId(request.getName(), moduleId).isPresent()) {
            throw new IllegalArgumentException(
                    "Environment '" + request.getName() + "' already exists for this module");
        }

        Environment env = new Environment();
        env.setModule(module);
        env.setName(request.getName().toUpperCase());

        if (request.getVariables() != null && !request.getVariables().isEmpty()) {
            env.setVariablesJson(encryptVariables(request.getVariables()));
        }

        return toResponse(environmentRepository.save(env));
    }

    public EnvironmentResponse update(Long envId, EnvironmentRequest request) {
        Environment env = getEntityById(envId);
        env.setName(request.getName().toUpperCase());

        if (request.getVariables() != null && !request.getVariables().isEmpty()) {
            env.setVariablesJson(encryptVariables(request.getVariables()));
        } else {
            env.setVariablesJson(null);
        }

        return toResponse(environmentRepository.save(env));
    }

    public EnvironmentResponse getById(Long envId) {
        return toResponse(getEntityById(envId));
    }

    public List<EnvironmentResponse> getByModuleId(Long moduleId) {
        return environmentRepository.findByModuleId(moduleId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public void delete(Long envId) {
        // ensure env exists (throws if not)
        Environment env = getEntityById(envId);

        // find flows that reference this environment as their default
        List<FlowDefinition> referencingFlows = flowRepository.findByDefaultEnvironment_Id(envId);

        if (!referencingFlows.isEmpty()) {
            // unset the relationship and persist
            referencingFlows.forEach(f -> f.setDefaultEnvironment(null));
            flowRepository.saveAll(referencingFlows);
        }

        // find modules that reference this environment as their default
        List<ModuleEntity> referencingModules = moduleRepository.findByDefaultEnvironment_Id(envId);
        if (!referencingModules.isEmpty()) {
            referencingModules.forEach(m -> m.setDefaultEnvironment(null));
            moduleRepository.saveAll(referencingModules);
        }

        environmentRepository.delete(env);
    }

    /**
     * Decrypt and return variables map for use during execution.
     */
    public Map<String, String> getDecryptedVariables(Long envId) {
        Environment env = getEntityById(envId);
        if (env.getVariablesJson() == null || env.getVariablesJson().isEmpty()) {
            return new LinkedHashMap<>();
        }
        return decryptVariables(env.getVariablesJson());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Environment getEntityById(Long envId) {
        return environmentRepository.findById(envId)
                .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + envId));
    }

    private String encryptVariables(Map<String, String> variables) {
        try {
            // Encrypt each value individually
            Map<String, String> encrypted = new LinkedHashMap<>();
            variables.forEach((k, v) -> encrypted.put(k, encryptionService.encrypt(v)));
            return objectMapper.writeValueAsString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt variables", e);
        }
    }

    private Map<String, String> decryptVariables(String variablesJson) {
        try {
            Map<String, String> encrypted = objectMapper.readValue(
                    variablesJson, new TypeReference<>() {});
            Map<String, String> decrypted = new LinkedHashMap<>();
            encrypted.forEach((k, v) -> decrypted.put(k, encryptionService.decrypt(v)));
            return decrypted;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt variables", e);
        }
    }

    private EnvironmentResponse toResponse(Environment env) {
        EnvironmentResponse response = new EnvironmentResponse();
        response.setId(env.getId());
        response.setName(env.getName());
        response.setModuleId(env.getModule().getId());
        if (env.getVariablesJson() == null || env.getVariablesJson().isEmpty()) {
            response.setVariables(new LinkedHashMap<>());
        } else {
            response.setVariables(decryptVariables(env.getVariablesJson()));
        }
        return response;
    }
}