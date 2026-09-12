package com.onderogluserdar.ticketing.config;

import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

/** Development convenience only: organizer and admin accounts cannot be self-registered. */
@Component
@Profile("dev")
class DevelopmentUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentUserSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String seedPassword;

    DevelopmentUserSeeder(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${ticketing.dev.seed-password}") String seedPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.seedPassword = seedPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        int seeded = seed("admin@example.com", Role.ADMIN)
                + seed("organizer@example.com", Role.ORGANIZER)
                + seed("customer@example.com", Role.CUSTOMER);
        log.info("Development profile: seeded {} of 3 users", seeded);
    }

    private int seed(String email, Role role) {
        if (users.existsByEmail(email)) {
            return 0;
        }
        users.save(User.create(email, passwordEncoder.encode(seedPassword), Set.of(role), Instant.now()));
        return 1;
    }
}
