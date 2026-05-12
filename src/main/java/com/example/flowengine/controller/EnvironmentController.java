package com.example.flowengine.controller;

import com.example.flowengine.DTO.EnvironmentRequest;
import com.example.flowengine.DTO.EnvironmentResponse;
import com.example.flowengine.service.EnvironmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/modules/{moduleId}/environments")
@RequiredArgsConstructor
@Tag(name = "Environments", description = "Manage environments for modules")
public class EnvironmentController {

    private final EnvironmentService environmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a env", description = "Create a new env for a module")
    public EnvironmentResponse create(@PathVariable Long moduleId,
                                      @Valid @RequestBody EnvironmentRequest request) {
        return environmentService.create(moduleId, request);
    }

    @GetMapping
    @Operation(summary = "Get Envs", description = "Get all Envs")
    public List<EnvironmentResponse> getAll(@PathVariable Long moduleId) {
        return environmentService.getByModuleId(moduleId);
    }

    @GetMapping("/{envId}")
    @Operation(summary = "Get Env", description = "Get Envs for a module")
    public EnvironmentResponse getById(@PathVariable Long moduleId,
                                       @PathVariable Long envId) {
        return environmentService.getById(envId);
    }

    @PutMapping("/{envId}")
    @Operation(summary = "UpdateEnv", description = "Update an existing Env for a module")
    public EnvironmentResponse update(@PathVariable Long moduleId,
                                      @PathVariable Long envId,
                                      @Valid @RequestBody EnvironmentRequest request) {
        return environmentService.update(envId, request);
    }

    @DeleteMapping("/{envId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete Envs", description = "Delete an Env for a module")
    public void delete(@PathVariable Long moduleId,
                       @PathVariable Long envId) {
        environmentService.delete(envId);
    }
}