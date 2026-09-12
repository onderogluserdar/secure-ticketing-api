package com.onderogluserdar.ticketing.reservation.dto;

import java.time.Instant;
import java.util.UUID;

import com.onderogluserdar.ticketing.reservation.Reservation;
import com.onderogluserdar.ticketing.reservation.ReservationStatus;

public record ReservationResponse(
        UUID id, UUID eventId, UUID userId, ReservationStatus status, int seats, Instant createdAt) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getUserId(),
                reservation.getStatus(),
                reservation.getSeats(),
                reservation.getCreatedAt());
    }
}
