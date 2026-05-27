package com.example.flowengine.repository;

import com.example.flowengine.entity.CustomMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomMethodRepository extends JpaRepository<CustomMethod, Long> {

    // All saved global methods — shown in the method picker for any flow
    List<CustomMethod> findByGlobalTrueOrderByNameAsc();

    // All methods (global + drafts) — for admin/management views
    List<CustomMethod> findAllByOrderByCreatedAtDesc();
}