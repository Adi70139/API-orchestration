package com.example.flowengine.repository;

import com.example.flowengine.entity.StepExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StepExecutionRepository extends JpaRepository<StepExecution, Long> {

    // All executions of a specific step across all flow runs, newest first
    @Query("""
        SELECT se FROM StepExecution se
        JOIN se.flowExecution fe
        WHERE se.stepId = :stepId
        ORDER BY fe.startedAt DESC
    """)
    List<StepExecution> findByStepIdOrderByStartedAtDesc(@Param("stepId") Long stepId);

    // Last N executions of a step — for trend window
    @Query(value = """
        SELECT se.* FROM step_executions se
        JOIN flow_executions fe ON se.flow_execution_id = fe.id
        WHERE se.step_id = :stepId
        ORDER BY fe.started_at DESC
        LIMIT :limit
    """, nativeQuery = true)
    List<StepExecution> findLastNByStepId(@Param("stepId") Long stepId, @Param("limit") int limit);

    // All step executions for a flow execution
    List<StepExecution> findByFlowExecutionIdOrderByStepOrderAsc(Long flowExecutionId);

    // All step executions across all runs of a flow (for per-step trend on flow history page)
    @Query("""
        SELECT se FROM StepExecution se
        JOIN se.flowExecution fe
        WHERE fe.flow.id = :flowId
        ORDER BY fe.startedAt DESC, se.stepOrder ASC
    """)
    List<StepExecution> findAllByFlowIdOrdered(@Param("flowId") Long flowId);
}