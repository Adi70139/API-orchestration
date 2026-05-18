package com.example.flowengine.repository;

import com.example.flowengine.entity.FlowExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FlowExecutionRepository extends JpaRepository<FlowExecution, Long> {


    // All executions for a flow, newest first
    List<FlowExecution> findByFlowIdOrderByStartedAtDesc(Long flowId);

    // Last N executions for a flow — used for trend window
    List<FlowExecution> findByFlowIdOrderByStartedAtDesc(Long flowId, Pageable pageable);

    default Optional<FlowExecution> findLatestByFlowId(Long flowId) {
        List<FlowExecution> results = findByFlowIdOrderByStartedAtDesc(flowId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}