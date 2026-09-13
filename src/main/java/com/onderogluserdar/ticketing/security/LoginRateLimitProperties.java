package com.onderogluserdar.ticketing.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 5 per minute is an assignment-level policy, not a universal value: production thresholds belong to
 * measured authentication traffic and false-positive tolerance.
 */
@ConfigurationProperties(prefix = "ticketing.rate-limit.login")
record LoginRateLimitProperties(int capacity, Duration refillPeriod) {

    LoginRateLimitProperties {
        if (capacity < 1) {
            throw new IllegalStateException("ticketing.rate-limit.login.capacity must be at least 1");
        }
        if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalStateException("ticketing.rate-limit.login.refill-period must be positive");
        }
    }
}
