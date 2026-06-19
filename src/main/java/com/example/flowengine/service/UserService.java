package com.example.flowengine.service;

import com.example.flowengine.entity.Feedback;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.entity.User;
import com.example.flowengine.repository.FeedbackRepository;
import com.example.flowengine.repository.ModuleRepository;
import com.example.flowengine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final ModuleRepository moduleRepository;
    private final FeedbackRepository feedbackRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void deleteUserAndData(Long userId) {
        log.info("Deleting user {} and all associated data", userId);
        
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Delete all feedback by user
        List<Feedback> userFeedbacks = feedbackRepository.findByUserId(userId);
        if (!userFeedbacks.isEmpty()) {
            feedbackRepository.deleteAll(userFeedbacks);
            log.info("Deleted {} feedbacks for user {}", userFeedbacks.size(), userId);
        }

        // Delete all modules created by user
        List<ModuleEntity> userModules = moduleRepository.findByCreatedById(userId);
        if (!userModules.isEmpty()) {
            moduleRepository.deleteAll(userModules);
            log.info("Deleted {} modules for user {}", userModules.size(), userId);
        }

        // Delete the user
        userRepository.delete(user);
        log.info("Deleted user {}", userId);
    }

    @Transactional
    public User updateUserProfile(Long userId, String name, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (name != null && !name.trim().isEmpty()) {
            user.setName(name);
        }
        if (password != null && !password.trim().isEmpty()) {
            user.setPassword(passwordEncoder.encode(password));
        }
        return userRepository.save(user);
    }
}
