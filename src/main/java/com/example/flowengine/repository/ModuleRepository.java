
package com.example.flowengine.repository;

import com.example.flowengine.entity.ModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ModuleRepository extends JpaRepository<ModuleEntity, Long> {

       Optional<ModuleEntity> findByName(String name);
}
