package com.example.flowengine.repository;

import com.example.flowengine.entity.BulkJobItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkJobItemRepository extends JpaRepository<BulkJobItem, Long> {
}