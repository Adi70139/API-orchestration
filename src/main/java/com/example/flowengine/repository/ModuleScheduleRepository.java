package com.example.flowengine.repository;

import com.example.flowengine.entity.ModuleSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModuleScheduleRepository extends JpaRepository<ModuleSchedule, Long> {
    Optional<ModuleSchedule> findByModuleId(Long moduleId);
    List<ModuleSchedule> findAllByActiveTrue();
}