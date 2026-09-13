package com.onderogluserdar.ticketing.event;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.audit.AuditAction;
import com.onderogluserdar.ticketing.audit.AuditService;
import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.event.dto.EventRequest;
import com.onderogluserdar.ticketing.reservation.ReservationRepository;
import com.onderogluserdar.ticketing.security.CurrentUser;

@Service
public class EventService {

    private final EventRepository events;
    private final ReservationRepository reservations;
    private final AuditService audit;

    public EventService(EventRepository events, ReservationRepository reservations, AuditService audit) {
        this.events = events;
        this.reservations = reservations;
        this.audit = audit;
    }

    @Transactional
    public Event create(CurrentUser caller, EventRequest request) {
        Event created = events.save(Event.createDraft(
                caller.id(),
                request.title(),
                request.venue(),
                request.startsAt(),
                request.endsAt(),
                request.capacity()));
        audit.record(AuditAction.EVENT_CREATED, caller.id(), "Event", created.getId());
        return created;
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

        audit.record(AuditAction.EVENT_UPDATED, caller.id(), "Event", eventId);
        return event;
    }

    @Transactional
    public Event publish(UUID eventId, CurrentUser caller) {
        Event event = events.findById(eventId).orElseThrow(() -> notFound(eventId));
        requireManageable(event, caller);
        event.publish();
        audit.record(AuditAction.EVENT_PUBLISHED, caller.id(), "Event", eventId);
        return event;
    }

    @Transactional(readOnly = true)
    public Page<Event> findEvents(UUID requestedOwnerId, CurrentUser caller, Pageable pageable) {
        if (caller.isAdmin()) {
            return requestedOwnerId == null
                    ? events.findAll(pageable)
                    : events.findAllByOwnerId(requestedOwnerId, pageable);
        }
        if (requestedOwnerId != null && !requestedOwnerId.equals(caller.id())) {
            throw new BusinessException(ErrorCode.EVENT_ACCESS_DENIED, "an organizer may only list their own events");
        }
        return events.findAllByOwnerId(caller.id(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<Event> discover(Instant from, Instant to, String query, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "from must not be after to");
        }
        return events.findPublished(from, to, blankToNull(query), pageable);
    }

    private static String blankToNull(String query) {
        return query == null || query.isBlank() ? null : query.trim();
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
