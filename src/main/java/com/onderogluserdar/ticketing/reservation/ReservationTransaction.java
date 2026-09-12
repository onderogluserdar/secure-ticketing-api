package com.onderogluserdar.ticketing.reservation;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.idempotency.IdempotencyKey;
import com.onderogluserdar.ticketing.idempotency.IdempotencyKeyRepository;
import com.onderogluserdar.ticketing.reservation.dto.ReservationResponse;
import com.onderogluserdar.ticketing.security.CurrentUser;

import tools.jackson.databind.ObjectMapper;

@Service
public class ReservationTransaction {

    private final IdempotencyKeyRepository keys;
    private final ReservationService reservations;
    private final ObjectMapper objectMapper;

    ReservationTransaction(IdempotencyKeyRepository keys, ReservationService reservations, ObjectMapper objectMapper) {
        this.keys = keys;
        this.reservations = reservations;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IdempotentReservationService.Outcome claimAndReserve(
            CurrentUser caller,
            String endpoint,
            String key,
            String requestHash,
            Duration timeToLive,
            UUID eventId,
            int seats) {
        // Flushed immediately so the unique index decides the race here, before any work is done.
        IdempotencyKey claim = keys.saveAndFlush(
                IdempotencyKey.claim(caller.id(), endpoint, key, requestHash, Instant.now(), timeToLive));

        Reservation reservation = reservations.reserve(eventId, seats, caller);
        ReservationResponse response = ReservationResponse.from(reservation);

        claim.complete(HttpStatus.CREATED.value(), objectMapper.writeValueAsString(response));

        return new IdempotentReservationService.Outcome(HttpStatus.CREATED.value(), response);
    }
}
