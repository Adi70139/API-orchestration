package com.example.flowengine.security;

import com.example.flowengine.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static Optional<User> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        Object principal = auth.getPrincipal();
        if (principal instanceof User user) return Optional.of(user);
        return Optional.empty();
    }

    public static User currentUserOrThrow() {
        return currentUser().orElseThrow(() ->
                new IllegalStateException("No authenticated user in security context"));
    }

    public static boolean isAdmin() {
        return currentUser()
                .map(u -> u.getRole() == User.Role.ADMIN)
                .orElse(false);
    }
}