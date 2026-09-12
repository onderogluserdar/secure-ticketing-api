package com.onderogluserdar.ticketing.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketing.jwt")
record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {

    private static final int MIN_SECRET_BYTES = 32;

    JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("ticketing.jwt.secret must be configured");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException("ticketing.jwt.secret must be at least %d bytes for HS256, got %d"
                    .formatted(MIN_SECRET_BYTES, bytes));
        }
        requirePositive(accessTokenTtl, "ticketing.jwt.access-token-ttl");
        requirePositive(refreshTokenTtl, "ticketing.jwt.refresh-token-ttl");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be a positive duration");
        }
    }
}
