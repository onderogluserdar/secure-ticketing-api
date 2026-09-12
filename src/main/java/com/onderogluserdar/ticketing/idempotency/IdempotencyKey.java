package com.onderogluserdar.ticketing.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String endpoint;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "response_hash")
    private String responseHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKey() {}

    public static IdempotencyKey claim(
            UUID userId, String endpoint, String key, String requestHash, Instant now, Duration timeToLive) {
        IdempotencyKey claim = new IdempotencyKey();
        claim.id = UUID.randomUUID();
        claim.userId = userId;
        claim.endpoint = endpoint;
        claim.idempotencyKey = key;
        claim.requestHash = requestHash;
        claim.status = IdempotencyStatus.IN_PROGRESS;
        claim.createdAt = now;
        claim.expiresAt = now.plus(timeToLive);
        return claim;
    }

    public void complete(int responseStatus, String responseBody) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.responseHash = sha256(responseBody);
        this.status = IdempotencyStatus.COMPLETED;
    }

    public boolean matches(String candidateRequestHash) {
        return MessageDigest.isEqual(
                requestHash.getBytes(StandardCharsets.UTF_8), candidateRequestHash.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isReplayable() {
        return status == IdempotencyStatus.COMPLETED && responseStatus != null && responseBody != null;
    }

    public boolean hasExpiredBy(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the platform", unavailable);
        }
    }

    public UUID getId() {
        return id;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
