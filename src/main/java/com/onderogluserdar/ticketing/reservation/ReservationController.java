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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Reservations", description = "Seat reservations for customers and admins.")
@RestController
class ReservationController {

    private final ReservationService reservationService;
    private final IdempotentReservationService idempotentReservations;

    ReservationController(ReservationService reservationService, IdempotentReservationService idempotentReservations) {
        this.reservationService = reservationService;
        this.idempotentReservations = idempotentReservations;
    }

    @Operation(
            summary = "Reserve seats",
            description = "Requires Idempotency-Key. Repeating the same key and payload replays the"
                    + " original result")
    @ApiResponse(responseCode = "201", description = "Created, or the replayed original result")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid seat count, or a blank or oversized Idempotency-Key",
            content = @Content)
    @ApiResponse(responseCode = "403", description = "Role may not reserve seats", content = @Content)
    @ApiResponse(
            responseCode = "409",
            description = "Insufficient capacity, unpublished event, or key conflict",
            content = @Content)
    @PostMapping("/api/events/{eventId}/reservations")
    ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID eventId,
            @Valid @RequestBody ReservationRequest request,
            @Parameter(description = "Client-generated key, at most 200 characters", in = ParameterIn.HEADER)
                    @RequestHeader("Idempotency-Key")
                    String idempotencyKey,
            Authentication authentication) {
        IdempotentReservationService.Outcome outcome = idempotentReservations.reserve(
                idempotencyKey, eventId, request.seats(), CurrentUser.from(authentication));
        return ResponseEntity.status(outcome.status()).body(outcome.response());
    }

    @Operation(summary = "Confirm a reservation", description = "Owner or admin. Inventory is unchanged.")
    @ApiResponse(responseCode = "200", description = "Confirmed")
    @ApiResponse(responseCode = "403", description = "Belongs to another customer", content = @Content)
    @ApiResponse(responseCode = "409", description = "Not in a confirmable state", content = @Content)
    @PostMapping("/api/reservations/{id}/confirm")
    ReservationResponse confirm(@PathVariable UUID id, Authentication authentication) {
        return ReservationResponse.from(reservationService.confirm(id, CurrentUser.from(authentication)));
    }

    @Operation(summary = "Cancel a reservation", description = "Owner or admin. Releases the seats.")
    @ApiResponse(responseCode = "200", description = "Cancelled")
    @ApiResponse(responseCode = "409", description = "Already cancelled", content = @Content)
    @PostMapping("/api/reservations/{id}/cancel")
    ReservationResponse cancel(@PathVariable UUID id, Authentication authentication) {
        return ReservationResponse.from(reservationService.cancel(id, CurrentUser.from(authentication)));
    }
}
