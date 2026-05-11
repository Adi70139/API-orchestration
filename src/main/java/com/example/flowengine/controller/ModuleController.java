
package com.example.flowengine.controller;

import com.example.flowengine.DTO.ModuleResponse;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.service.ModuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/modules")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService service;

    @PostMapping
    public ModuleEntity create(@RequestBody ModuleEntity module) {
        return service.create(module);
    }

    @GetMapping
    public List<ModuleResponse> getAll() {
        return service.getAll();
    }

    @DeleteMapping("/{moduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteModule(@PathVariable Long moduleId) {
        service.delete(moduleId);
    }
}
