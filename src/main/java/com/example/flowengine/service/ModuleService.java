
package com.example.flowengine.service;

import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.repository.ModuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ModuleService {

    private final ModuleRepository repository;

    public ModuleEntity create(ModuleEntity module) {
        return repository.save(module);
    }

    public List<ModuleEntity> getAll() {
        return repository.findAll();
    }
}
