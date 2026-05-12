
package com.example.flowengine.controller;

import com.example.flowengine.DTO.ModuleResponse;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.service.ModuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/modules")
@RequiredArgsConstructor
@Tag(name = "Module Creation", description = "Create and manage modules")
public class ModuleController {

    private final ModuleService service;

    @PostMapping
    @Operation(summary = "Create module", description = "Create a new module.")
    public ModuleEntity create(@RequestBody ModuleEntity module) {
        return service.create(module);
    }

    @GetMapping
    @Operation(summary = "Get all modules", description = "Get all modules.")
    public List<ModuleResponse> getAll() {
        return service.getAll();
    }

    @DeleteMapping("/{moduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a module", description = "Delete a module by its ID.")
    public void deleteModule(@PathVariable Long moduleId) {
        service.delete(moduleId);
    }
}
