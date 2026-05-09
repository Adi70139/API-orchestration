package com.example.flowengine.repository;

import com.example.flowengine.entity.ModuleExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModuleExecutionRepository extends JpaRepository<ModuleExecution, Long> {
}