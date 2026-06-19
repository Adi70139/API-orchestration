package com.example.flowengine.repository;

import com.example.flowengine.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    Page<Feedback> findByUserId(Long userId, Pageable pageable);
    List<Feedback> findByUserId(Long userId);
    Page<Feedback> findByStatus(Feedback.FeedbackStatus status, Pageable pageable);
    Page<Feedback> findByType(Feedback.FeedbackType type, Pageable pageable);
}