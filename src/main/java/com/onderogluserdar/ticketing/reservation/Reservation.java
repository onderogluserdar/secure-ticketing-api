package com.onderogluserdar.ticketing.reservation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false, updatable = false)
    private int seats;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Reservation() {}

    public static Reservation createPending(UUID eventId, UUID userId, int seats, Instant now) {
        if (seats < 1) {
            throw new IllegalArgumentException("seats must be at least 1");
        }
        Reservation reservation = new Reservation();
        reservation.id = UUID.randomUUID();
        reservation.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        reservation.userId = Objects.requireNonNull(userId, "userId must not be null");
        reservation.seats = seats;
        reservation.status = ReservationStatus.PENDING;
        reservation.createdAt = Objects.requireNonNull(now, "now must not be null");
        return reservation;
    }

    public void confirm() {
        if (status != ReservationStatus.PENDING) {
            throw new BusinessException(
                    ErrorCode.INVALID_RESERVATION_STATE,
                    "only a pending reservation can be confirmed, this one is %s".formatted(status));
        }
        status = ReservationStatus.CONFIRMED;
    }

    public void cancel() {
        if (status == ReservationStatus.CANCELLED) {
            throw new BusinessException(
                    ErrorCode.INVALID_RESERVATION_STATE, "reservation %s is already cancelled".formatted(id));
        }
        status = ReservationStatus.CANCELLED;
    }

    public boolean isOwnedBy(UUID candidate) {
        return userId.equals(candidate);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getUserId() {
        return userId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public int getSeats() {
        return seats;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
