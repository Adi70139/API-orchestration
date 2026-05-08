
package com.example.flowengine.controller;

import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.service.ModuleService;
import lombok.RequiredArgsConstructor;
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
    public List<ModuleEntity> getAll() {
        return service.getAll();
    }
}
