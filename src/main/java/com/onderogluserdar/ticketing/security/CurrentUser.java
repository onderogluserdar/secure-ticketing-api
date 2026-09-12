package com.onderogluserdar.ticketing.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import com.onderogluserdar.ticketing.user.Role;

/** The caller as the access token describes them, so ownership checks need no database read. */
public record CurrentUser(UUID id, Set<Role> roles) {

    private static final String AUTHORITY_PREFIX = "ROLE_";

    public CurrentUser {
        roles = Set.copyOf(roles);
    }

    public static CurrentUser from(Authentication authentication) {
        Set<Role> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(AUTHORITY_PREFIX))
                .map(authority -> authority.substring(AUTHORITY_PREFIX.length()))
                .map(Role::valueOf)
                .collect(Collectors.toUnmodifiableSet());

        return new CurrentUser(UUID.fromString(authentication.getName()), roles);
    }

    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }
}
