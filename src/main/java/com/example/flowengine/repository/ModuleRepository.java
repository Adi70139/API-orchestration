
package com.example.flowengine.repository;

import com.example.flowengine.entity.ModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface ModuleRepository extends JpaRepository<ModuleEntity, Long> {

       Optional<ModuleEntity> findByName(String name);

       // find modules which have this environment set as their defaultEnvironment
       List<ModuleEntity> findByDefaultEnvironment_Id(Long envId);

       List<ModuleEntity> findByCreatedById(Long createdById);
}
