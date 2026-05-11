package com.example.flowengine.repository;

import com.example.flowengine.entity.Environment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvironmentRepository extends JpaRepository<Environment, Long> {
    List<Environment> findByModuleId(Long moduleId);
    Optional<Environment> findByNameAndModuleId(String name, Long moduleId);
}