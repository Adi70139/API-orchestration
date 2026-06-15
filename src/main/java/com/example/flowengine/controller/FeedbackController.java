package com.example.flowengine.controller;

import com.example.flowengine.entity.Feedback;
import com.example.flowengine.entity.User;
import com.example.flowengine.repository.FeedbackRepository;
import com.example.flowengine.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/feedback")
@RequiredArgsConstructor
@Tag(name = "Feedback", description = "Submit and manage user feedback and issue reports")
public class FeedbackController {

    private final FeedbackRepository feedbackRepository;

    // ── Submit feedback (any authenticated user) ──────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a bug report, feature request, or general feedback")
    public FeedbackResponse submit(@RequestBody FeedbackRequest req,
                                   HttpServletRequest httpRequest) {
        if (req.getTitle() == null || req.getTitle().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title is required");
        if (req.getDescription() == null || req.getDescription().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description is required");

        User currentUser = SecurityUtils.currentUserOrThrow();

        Feedback feedback = new Feedback();
        feedback.setUser(currentUser);
        feedback.setTitle(req.getTitle().trim());
        feedback.setDescription(req.getDescription().trim());
        feedback.setType(req.getType() != null ? req.getType() : Feedback.FeedbackType.BUG);
        feedback.setSeverity(req.getSeverity() != null ? req.getSeverity() : Feedback.Severity.MEDIUM);
        feedback.setPageUrl(req.getPageUrl());
        feedback.setUserAgent(httpRequest.getHeader("User-Agent"));
        feedback.setCreatedAt(LocalDateTime.now());
        feedback.setUpdatedAt(LocalDateTime.now());

        return toResponse(feedbackRepository.save(feedback));
    }

    // ── My feedback (current user) ────────────────────────────────────────────

    @GetMapping("/mine")
    @Operation(summary = "Get feedback submitted by the current user")
    public Page<FeedbackResponse> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        User currentUser = SecurityUtils.currentUserOrThrow();
        return feedbackRepository.findByUserId(currentUser.getId(),
                        PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(this::toResponse);
    }

    // ── Admin endpoints ───────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List all feedback (admin only)")
    public Page<FeedbackResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Feedback.FeedbackStatus status,
            @RequestParam(required = false) Feedback.FeedbackType type) {

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        if (status != null) return feedbackRepository.findByStatus(status, pageable).map(this::toResponse);
        if (type   != null) return feedbackRepository.findByType(type, pageable).map(this::toResponse);
        return feedbackRepository.findAll(pageable).map(this::toResponse);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update feedback status (admin only)")
    public FeedbackResponse updateStatus(
            @PathVariable Long id,
            @RequestParam Feedback.FeedbackStatus status) {
        Feedback feedback = feedbackRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feedback not found"));
        feedback.setStatus(status);
        feedback.setUpdatedAt(LocalDateTime.now());
        return toResponse(feedbackRepository.save(feedback));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Data
    public static class FeedbackRequest {
        private String title;
        private String description;
        private Feedback.FeedbackType type;
        private Feedback.Severity severity;
        private String pageUrl;  // frontend sends current URL automatically
    }

    @Data
    public static class FeedbackResponse {
        private Long id;
        private String submittedBy;  // user email
        private String title;
        private String description;
        private Feedback.FeedbackType type;
        private Feedback.Severity severity;
        private Feedback.FeedbackStatus status;
        private String pageUrl;
        private LocalDateTime createdAt;
    }

    private FeedbackResponse toResponse(Feedback f) {
        FeedbackResponse r = new FeedbackResponse();
        r.setId(f.getId());
        r.setSubmittedBy(f.getUser() != null ? f.getUser().getEmail() : "unknown");
        r.setTitle(f.getTitle());
        r.setDescription(f.getDescription());
        r.setType(f.getType());
        r.setSeverity(f.getSeverity());
        r.setStatus(f.getStatus());
        r.setPageUrl(f.getPageUrl());
        r.setCreatedAt(f.getCreatedAt());
        return r;
    }
}