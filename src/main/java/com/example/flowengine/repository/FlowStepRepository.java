
package com.example.flowengine.repository;

import com.example.flowengine.entity.FlowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FlowStepRepository extends JpaRepository<FlowStep, Long> {

    List<FlowStep> findByFlowIdOrderByStepOrder(Long flowId);

    @Query("""
       SELECT COALESCE(MAX(fs.stepOrder), 0)
       FROM FlowStep fs
       WHERE fs.flow.id = :flowId
       """)
    Integer findMaxStepOrderByFlowId(Long flowId);
}
