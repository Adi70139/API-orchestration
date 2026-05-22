
package com.example.flowengine.repository;

import com.example.flowengine.entity.FlowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FlowRepository extends JpaRepository<FlowDefinition, Long> {

    List<FlowDefinition> findByModuleId(Long moduleId);

    List<FlowDefinition> findByModule_Name(String moduleName);

    Optional<FlowDefinition> findByNameAndModule_Id(String name, Long moduleId);

    // find flows which have this environment set as their defaultEnvironment
    List<FlowDefinition> findByDefaultEnvironment_Id(Long envId);
}
