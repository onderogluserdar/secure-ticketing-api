package com.onderogluserdar.ticketing.event;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.event.dto.EventPageResponse;
import com.onderogluserdar.ticketing.event.dto.EventRequest;
import com.onderogluserdar.ticketing.event.dto.EventResponse;
import com.onderogluserdar.ticketing.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Events", description = "Event management for organizers and admins, plus public discovery.")
@RestController
@RequestMapping("/api/events")
class EventController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EventService eventService;

    EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @Operation(summary = "Create a draft event", description = "The owner is the authenticated caller.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "403", description = "Role may not create events", content = @Content)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EventResponse create(@Valid @RequestBody EventRequest request, Authentication authentication) {
        return EventResponse.from(eventService.create(CurrentUser.from(authentication), request));
    }

    @Operation(summary = "Replace event details", description = "Capacity may not drop below reserved seats.")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "403", description = "Owned by another organizer", content = @Content)
    @ApiResponse(responseCode = "409", description = "Capacity below seats already reserved", content = @Content)
    @PutMapping("/{id}")
    EventResponse update(
            @PathVariable UUID id, @Valid @RequestBody EventRequest request, Authentication authentication) {
        return EventResponse.from(eventService.update(id, CurrentUser.from(authentication), request));
    }

    @Operation(summary = "Publish an event", description = "Only a draft can be published.")
    @ApiResponse(responseCode = "200", description = "Published")
    @ApiResponse(responseCode = "409", description = "Already published", content = @Content)
    @PostMapping("/{id}/publish")
    EventResponse publish(@PathVariable UUID id, Authentication authentication) {
        return EventResponse.from(eventService.publish(id, CurrentUser.from(authentication)));
    }

    @Operation(
            summary = "List events by owner",
            description = "An organizer may only list their own events; an admin may list any owner.")
    @GetMapping
    EventPageResponse list(
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return EventPageResponse.from(
                eventService.findEvents(ownerId, CurrentUser.from(authentication), pageOf(page, size)));
    }

    @Operation(summary = "Discover published events", description = "Public. Published events only.")
    @SecurityRequirements
    @GetMapping("/public")
    EventPageResponse discover(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return EventPageResponse.from(eventService.discover(from, to, q, pageOf(page, size)));
    }

    /** Sorted by id as well as start time */
    private static PageRequest pageOf(int page, int size) {
        if (page < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(
                page, size, Sort.by("startsAt").ascending().and(Sort.by("id").ascending()));
    }
}
