package com.example.flowengine.controller;

import com.example.flowengine.entity.User;
import com.example.flowengine.repository.UserRepository;
import com.example.flowengine.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Register, login, and Google OAuth2")
public class AuthController {

    private final UserRepository userRepository;
    private final AuthenticationManager authManager;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    // ── Register ──────────────────────────────────────────────────────────────

    @PostMapping("/register")
    @Operation(summary = "Register a new local user")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User();
        user.setEmail(req.getEmail());
        user.setName(req.getName());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(User.Role.USER);
        user.setProvider(User.Provider.LOCAL);
        user.setCreatedAt(LocalDateTime.now());
        userRepository.save(user);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(jwtUtil.generate(user), toProfile(user)));
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @PostMapping("/login")
    @Operation(summary = "Login with email and password")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        try {
            Authentication auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
            User user = (User) auth.getPrincipal();
            return ResponseEntity.ok(new AuthResponse(jwtUtil.generate(user), toProfile(user)));
        } catch (BadCredentialsException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
    }

    // ── Me (current user) ─────────────────────────────────────────────────────

    @GetMapping("/me")
    @Operation(summary = "Get current authenticated user's profile")
    public ResponseEntity<UserProfile> me(Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        return ResponseEntity.ok(toProfile(user));
    }

    // ── Google OAuth2 note ────────────────────────────────────────────────────
    // Google login is handled by Spring Security OAuth2 at /oauth2/authorization/google
    // On success, SecurityConfig redirects to the frontend with ?token=<jwt>
    // No explicit endpoint needed here.

    @GetMapping("/google-login-url")
    @Operation(summary = "Get the URL to initiate Google OAuth2 login")
    public ResponseEntity<Map<String, String>> googleLoginUrl(
            @RequestParam(defaultValue = "/") String redirectTo) {
        return ResponseEntity.ok(Map.of(
                "url", "/oauth2/authorization/google?state=" + redirectTo));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        private String email;

        @NotBlank(message = "Name is required")
        private String name;

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        private String password;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        private String email;

        @NotBlank(message = "Password is required")
        private String password;
    }

    @Data
    public static class AuthResponse {
        private final String token;
        private final UserProfile user;
    }

    @Data
    public static class UserProfile {
        private Long id;
        private String email;
        private String name;
        private String role;
        private String provider;
    }

    private UserProfile toProfile(User user) {
        UserProfile p = new UserProfile();
        p.setId(user.getId());
        p.setEmail(user.getEmail());
        p.setName(user.getName());
        p.setRole(user.getRole().name());
        p.setProvider(user.getProvider().name());
        return p;
    }
}