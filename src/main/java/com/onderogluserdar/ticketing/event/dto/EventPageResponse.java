package com.onderogluserdar.ticketing.event.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.onderogluserdar.ticketing.event.Event;

public record EventPageResponse(List<EventResponse> content, int page, int size, long totalElements, int totalPages) {

    public EventPageResponse {
        content = List.copyOf(content);
    }

    public static EventPageResponse from(Page<Event> page) {
        return new EventPageResponse(
                page.getContent().stream().map(EventResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
