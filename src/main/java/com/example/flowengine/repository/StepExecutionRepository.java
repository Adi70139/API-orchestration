package com.example.flowengine.repository;

import com.example.flowengine.entity.StepExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StepExecutionRepository extends JpaRepository<StepExecution, Long> {
}
