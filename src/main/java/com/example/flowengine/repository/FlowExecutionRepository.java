package com.example.flowengine.repository;

import com.example.flowengine.constants.ExecutionStatus;
import com.example.flowengine.entity.FlowExecution;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FlowExecutionRepository extends JpaRepository<FlowExecution, Long> {


    /**
     * Update only execution state. This deliberately avoids merging the FlowExecution
     * aggregate and its lazy stepExecutions collection.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
    UPDATE FlowExecution fe
    SET fe.status = :status, fe.finishedAt = :finishedAt
    WHERE fe.id = :id
""")
    int updateExecutionStatus(@Param("id") Long id,
                              @Param("status") ExecutionStatus status,
                              @Param("finishedAt") LocalDateTime finishedAt);

    // All executions for a flow, newest first
    List<FlowExecution> findByFlowIdOrderByStartedAtDesc(Long flowId);

    // Last N executions for a flow — used for trend window
    List<FlowExecution> findByFlowIdOrderByStartedAtDesc(Long flowId, Pageable pageable);

    default Optional<FlowExecution> findLatestByFlowId(Long flowId) {
        List<FlowExecution> results = findByFlowIdOrderByStartedAtDesc(flowId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}