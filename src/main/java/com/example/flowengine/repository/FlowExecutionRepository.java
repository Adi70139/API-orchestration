package com.example.flowengine.repository;

import com.example.flowengine.entity.FlowExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FlowExecutionRepository extends JpaRepository<FlowExecution, Long> {
    Optional<FlowExecution> findByFlowId(Long flowId);
}