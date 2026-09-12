package com.onderogluserdar.ticketing.reservation;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.onderogluserdar.ticketing.reservation.dto.ReservationRequest;
import com.onderogluserdar.ticketing.reservation.dto.ReservationResponse;
import com.onderogluserdar.ticketing.security.CurrentUser;

@RestController
class ReservationController {

    private final ReservationService reservationService;
    private final IdempotentReservationService idempotentReservations;

    ReservationController(ReservationService reservationService, IdempotentReservationService idempotentReservations) {
        this.reservationService = reservationService;
        this.idempotentReservations = idempotentReservations;
    }

    @PostMapping("/api/events/{eventId}/reservations")
    ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID eventId,
            @Valid @RequestBody ReservationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication) {
        IdempotentReservationService.Outcome outcome = idempotentReservations.reserve(
                idempotencyKey, eventId, request.seats(), CurrentUser.from(authentication));
        return ResponseEntity.status(outcome.status()).body(outcome.response());
    }

    @PostMapping("/api/reservations/{id}/confirm")
    ReservationResponse confirm(@PathVariable UUID id, Authentication authentication) {
        return ReservationResponse.from(reservationService.confirm(id, CurrentUser.from(authentication)));
    }

    @PostMapping("/api/reservations/{id}/cancel")
    ReservationResponse cancel(@PathVariable UUID id, Authentication authentication) {
        return ReservationResponse.from(reservationService.cancel(id, CurrentUser.from(authentication)));
    }
}
