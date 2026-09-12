package com.onderogluserdar.ticketing.event;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.event.dto.EventRequest;
import com.onderogluserdar.ticketing.reservation.ReservationRepository;
import com.onderogluserdar.ticketing.security.CurrentUser;

@Service
public class EventService {

    private final EventRepository events;
    private final ReservationRepository reservations;

    public EventService(EventRepository events, ReservationRepository reservations) {
        this.events = events;
        this.reservations = reservations;
    }

    @Transactional
    public Event create(CurrentUser caller, EventRequest request) {
        return events.save(Event.createDraft(
                caller.id(),
                request.title(),
                request.venue(),
                request.startsAt(),
                request.endsAt(),
                request.capacity()));
    }

    @Transactional
    public Event update(UUID eventId, CurrentUser caller, EventRequest request) {
        // Locked first, then the active seats are counted, so a concurrent reservation cannot slip
        // in between the count and the capacity change.
        Event event = events.findByIdForCapacityChange(eventId).orElseThrow(() -> notFound(eventId));
        requireManageable(event, caller);

        event.update(
                request.title(),
                request.venue(),
                request.startsAt(),
                request.endsAt(),
                request.capacity(),
                reservations.activeSeatsFor(eventId));

        return event;
    }

    @Transactional
    public Event publish(UUID eventId, CurrentUser caller) {
        Event event = events.findById(eventId).orElseThrow(() -> notFound(eventId));
        requireManageable(event, caller);
        event.publish();
        return event;
    }

    private static void requireManageable(Event event, CurrentUser caller) {
        if (!caller.isAdmin() && !event.isOwnedBy(caller.id())) {
            throw new BusinessException(ErrorCode.EVENT_ACCESS_DENIED, "event is owned by another organizer");
        }
    }

    private static BusinessException notFound(UUID eventId) {
        return new BusinessException(ErrorCode.EVENT_NOT_FOUND, "event %s does not exist".formatted(eventId));
    }
}
