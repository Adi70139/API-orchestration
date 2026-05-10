package com.example.flowengine.repository;

import com.example.flowengine.entity.BulkJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkJobRepository extends JpaRepository<BulkJob, Long> {
}