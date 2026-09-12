package com.onderogluserdar.ticketing.event.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EventRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 200) String venue,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @Min(1) int capacity) {}
