package com.onderogluserdar.ticketing.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    /** Compared against when no user matches, so an unknown email still costs a BCrypt verify. */
    private final String absentUserHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.absentUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public User register(String email, String rawPassword) {
        String normalized = User.normalizeEmail(email);
        if (users.existsByEmail(normalized)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED, "email is already registered");
        }
        try {
            // Flushed here rather than at commit, so a lost race is reported as the same error.
            return users.saveAndFlush(
                    User.create(normalized, passwordEncoder.encode(rawPassword), Set.of(Role.CUSTOMER), Instant.now()));
        } catch (DataIntegrityViolationException concurrentRegistration) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED, "email is already registered");
        }
    }

    @Transactional
    public User authenticate(String email, String rawPassword) {
        Optional<User> found = users.findByEmail(User.normalizeEmail(email));
        String hash = found.map(User::getPasswordHash).orElse(absentUserHash);
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hash);

        if (found.isEmpty() || !passwordMatches) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "email or password is incorrect");
        }

        User user = found.get();
        user.recordLogin(Instant.now());
        return user;
    }
}
