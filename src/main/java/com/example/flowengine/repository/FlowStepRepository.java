
package com.example.flowengine.repository;

import com.example.flowengine.entity.FlowStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlowStepRepository extends JpaRepository<FlowStep, Long> {

    List<FlowStep> findByFlowIdOrderByStepOrder(Long flowId);
}
