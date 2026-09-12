package com.onderogluserdar.ticketing.reservation.dto;

import jakarta.validation.constraints.Min;

public record ReservationRequest(@Min(1) int seats) {}
