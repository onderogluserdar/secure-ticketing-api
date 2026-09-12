package com.onderogluserdar.ticketing.auth.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;

public record UserResponse(UUID id, String email, Set<Role> roles, Instant createdAt) {

    public UserResponse {
        roles = Set.copyOf(roles);
    }

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRoles(), user.getCreatedAt());
    }
}
