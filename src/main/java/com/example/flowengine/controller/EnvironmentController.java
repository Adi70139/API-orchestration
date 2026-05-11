package com.example.flowengine.controller;

import com.example.flowengine.DTO.EnvironmentRequest;
import com.example.flowengine.DTO.EnvironmentResponse;
import com.example.flowengine.service.EnvironmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/modules/{moduleId}/environments")
@RequiredArgsConstructor
public class EnvironmentController {

    private final EnvironmentService environmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentResponse create(@PathVariable Long moduleId,
                                      @Valid @RequestBody EnvironmentRequest request) {
        return environmentService.create(moduleId, request);
    }

    @GetMapping
    public List<EnvironmentResponse> getAll(@PathVariable Long moduleId) {
        return environmentService.getByModuleId(moduleId);
    }

    @GetMapping("/{envId}")
    public EnvironmentResponse getById(@PathVariable Long moduleId,
                                       @PathVariable Long envId) {
        return environmentService.getById(envId);
    }

    @PutMapping("/{envId}")
    public EnvironmentResponse update(@PathVariable Long moduleId,
                                      @PathVariable Long envId,
                                      @Valid @RequestBody EnvironmentRequest request) {
        return environmentService.update(envId, request);
    }

    @DeleteMapping("/{envId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long moduleId,
                       @PathVariable Long envId) {
        environmentService.delete(envId);
    }
}