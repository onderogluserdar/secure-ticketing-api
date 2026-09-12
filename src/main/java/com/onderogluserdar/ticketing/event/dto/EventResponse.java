package com.onderogluserdar.ticketing.event.dto;

import java.time.Instant;
import java.util.UUID;

import com.onderogluserdar.ticketing.event.Event;

public record EventResponse(
        UUID id,
        UUID ownerId,
        String title,
        String venue,
        Instant startsAt,
        Instant endsAt,
        int capacity,
        boolean published) {

    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getOwnerId(),
                event.getTitle(),
                event.getVenue(),
                event.getStartsAt(),
                event.getEndsAt(),
                event.getCapacity(),
                event.isPublished());
    }
}
