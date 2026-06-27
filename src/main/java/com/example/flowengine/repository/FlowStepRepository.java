package com.example.flowengine.repository;

import com.example.flowengine.entity.FlowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FlowStepRepository extends JpaRepository<FlowStep, Long> {

    List<FlowStep> findByFlowIdOrderByStepOrder(Long flowId);

    @Query("""
       SELECT COALESCE(MAX(fs.stepOrder), 0)
       FROM FlowStep fs
       WHERE fs.flow.id = :flowId
       """)
    Integer findMaxStepOrderByFlowId(Long flowId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE flow_steps SET payload_variants_json = :variants WHERE id = :id",
            nativeQuery = true)
    void updatePayloadVariants(@Param("id") Long id,
                               @Param("variants") String variants);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE flow_steps SET last_response_body = :body WHERE id = :id",
            nativeQuery = true)
    void updateLastResponseBody(@Param("id") Long id, @Param("body") String body);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE flow_steps SET last_method_outputs_json = :json WHERE id = :id",
            nativeQuery = true)
    void updateLastMethodOutputs(@Param("id") Long id, @Param("json") String json);
}