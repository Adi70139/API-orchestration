package com.example.flowengine.repository;

import com.example.flowengine.entity.StepMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepMethodRepository extends JpaRepository<StepMethod, Long> {

    List<StepMethod> findByStepIdOrderByExecutionOrder(Long stepId);

    List<StepMethod> findByMethodId(Long methodId);

    void deleteByStepId(Long stepId);
}