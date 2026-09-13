package com.onderogluserdar.ticketing.security;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.common.error.ProblemDetails;
import com.onderogluserdar.ticketing.observability.TicketingMetrics;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import tools.jackson.databind.ObjectMapper;

/**
 * Only the unauthenticated login surface is limited, because that is where brute force and
 * credential stuffing apply.
 */
@Component
@EnableConfigurationProperties(LoginRateLimitProperties.class)
class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Map<String, Bucket> bucketsByClient = new ConcurrentHashMap<>();
    private final LoginRateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final TicketingMetrics metrics;

    LoginRateLimitFilter(LoginRateLimitProperties properties, ObjectMapper objectMapper, TicketingMetrics metrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(HttpMethod.POST.matches(request.getMethod()) && LOGIN_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = bucketsByClient.computeIfAbsent(clientKey(request), key -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }

        metrics.rateLimitRejected();
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(secondsUntilRefill(probe)));
        ProblemDetails.write(
                response,
                objectMapper,
                ProblemDetails.of(
                        HttpStatus.TOO_MANY_REQUESTS,
                        ErrorCode.RATE_LIMIT_EXCEEDED,
                        "Too many requests. Try again later."));
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(
                        properties.capacity(), Refill.greedy(properties.capacity(), properties.refillPeriod())))
                .build();
    }

    private static String clientKey(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null ? "unknown" : remoteAddress;
    }

    private static long secondsUntilRefill(ConsumptionProbe probe) {
        return Math.max(1, (probe.getNanosToWaitForRefill() + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
    }
}
