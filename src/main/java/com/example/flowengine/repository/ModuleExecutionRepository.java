package com.example.flowengine.repository;

import com.example.flowengine.entity.ModuleExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ModuleExecutionRepository extends JpaRepository<ModuleExecution, Long> {

    // All executions for a module, newest first — paginated
    Page<ModuleExecution> findByModuleIdOrderByStartedAtDesc(Long moduleId, Pageable pageable);

    // All executions for a module, newest first — unpaged
    List<ModuleExecution> findByModuleIdOrderByStartedAtDesc(Long moduleId);

    // Most recent execution for a module
    @Query("SELECT me FROM ModuleExecution me WHERE me.module.id = :moduleId ORDER BY me.startedAt DESC LIMIT 1")
    Optional<ModuleExecution> findLatestByModuleId(@Param("moduleId") Long moduleId);

    // All executions for a user's modules, newest first — paginated
    Page<ModuleExecution> findByModule_CreatedBy_IdOrderByStartedAtDesc(Long createdById, Pageable pageable);
}