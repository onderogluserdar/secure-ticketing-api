package com.onderogluserdar.ticketing.event;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.onderogluserdar.ticketing.event.dto.EventRequest;
import com.onderogluserdar.ticketing.event.dto.EventResponse;
import com.onderogluserdar.ticketing.security.CurrentUser;

@RestController
@RequestMapping("/api/events")
class EventController {

    private final EventService eventService;

    EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EventResponse create(@Valid @RequestBody EventRequest request, Authentication authentication) {
        return EventResponse.from(eventService.create(CurrentUser.from(authentication), request));
    }

    @PutMapping("/{id}")
    EventResponse update(
            @PathVariable UUID id, @Valid @RequestBody EventRequest request, Authentication authentication) {
        return EventResponse.from(eventService.update(id, CurrentUser.from(authentication), request));
    }

    @PostMapping("/{id}/publish")
    EventResponse publish(@PathVariable UUID id, Authentication authentication) {
        return EventResponse.from(eventService.publish(id, CurrentUser.from(authentication)));
    }
}
