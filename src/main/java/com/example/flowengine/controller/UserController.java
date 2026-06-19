package com.example.flowengine.controller;

import com.example.flowengine.entity.User;
import com.example.flowengine.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/users/me")
@RequiredArgsConstructor
@Tag(name = "User Profile", description = "User profile and dashboard operations")
public class UserController {

    private final UserService userService;

    @PutMapping
    @Operation(summary = "Update current user's profile")
    public ResponseEntity<AuthController.UserProfile> updateProfile(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest req) {
        User user = (User) authentication.getPrincipal();
        User updatedUser = userService.updateUserProfile(user.getId(), req.getName(), req.getPassword());
        return ResponseEntity.ok(toProfile(updatedUser));
    }

    @DeleteMapping
    @Operation(summary = "Delete current user's account and all associated data")
    public ResponseEntity<Void> deleteAccount(Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        userService.deleteUserAndData(user.getId());
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class UpdateProfileRequest {
        @NotBlank(message = "Name is required")
        private String name;
        
        private String password;
    }

    private AuthController.UserProfile toProfile(User user) {
        AuthController.UserProfile p = new AuthController.UserProfile();
        p.setId(user.getId());
        p.setEmail(user.getEmail());
        p.setName(user.getName());
        p.setRole(user.getRole().name());
        p.setProvider(user.getProvider().name());
        return p;
    }
}
