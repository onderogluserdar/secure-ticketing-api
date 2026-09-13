package com.onderogluserdar.ticketing.reservation;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.audit.AuditAction;
import com.onderogluserdar.ticketing.audit.AuditService;
import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.event.Event;
import com.onderogluserdar.ticketing.event.EventRepository;
import com.onderogluserdar.ticketing.security.CurrentUser;

/**
 * Two locks protecting two different things: the Event row guards inventory, the Reservation row
 * guards its own state machine. Where both are needed the Event is always locked first.
 */
@Service
public class ReservationService {

    private final ReservationRepository reservations;
    private final EventRepository events;
    private final AuditService audit;

    public ReservationService(ReservationRepository reservations, EventRepository events, AuditService audit) {
        this.reservations = reservations;
        this.events = events;
        this.audit = audit;
    }

    @Transactional
    public Reservation reserve(UUID eventId, int seats, CurrentUser caller) {
        Event event = events.findByIdForCapacityChange(eventId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.EVENT_NOT_FOUND, "event %s does not exist".formatted(eventId)));

        event.ensureCanAccommodate(seats, reservations.activeSeatsFor(eventId));

        Reservation created = reservations.save(Reservation.createPending(eventId, caller.id(), seats, Instant.now()));
        audit.record(AuditAction.RESERVATION_CREATED, caller.id(), "Reservation", created.getId());
        return created;
    }

    @Transactional
    public Reservation confirm(UUID reservationId, CurrentUser caller) {
        Reservation reservation = lockForStateChange(reservationId);
        requireOwner(reservation, caller);
        reservation.confirm();
        audit.record(AuditAction.RESERVATION_CONFIRMED, caller.id(), "Reservation", reservationId);
        return reservation;
    }

    @Transactional
    public Reservation cancel(UUID reservationId, CurrentUser caller) {
        Reservation reservation = reservations.findById(reservationId).orElseThrow(() -> notFound(reservationId));
        requireOwner(reservation, caller);

        lockInventoryOf(reservation.getEventId());
        Reservation locked = lockForStateChange(reservationId);
        locked.cancel();
        audit.record(AuditAction.RESERVATION_CANCELLED, caller.id(), "Reservation", reservationId);
        return locked;
    }

    /** Cancelling changes what the active-seat sum returns, so inventory is locked too. */
    private void lockInventoryOf(UUID eventId) {
        events.findByIdForCapacityChange(eventId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.EVENT_NOT_FOUND, "event %s does not exist".formatted(eventId)));
    }

    private Reservation lockForStateChange(UUID reservationId) {
        return reservations.findByIdForStateChange(reservationId).orElseThrow(() -> notFound(reservationId));
    }

    private static void requireOwner(Reservation reservation, CurrentUser caller) {
        if (!caller.isAdmin() && !reservation.isOwnedBy(caller.id())) {
            throw new BusinessException(ErrorCode.RESERVATION_ACCESS_DENIED, "reservation belongs to another customer");
        }
    }

    private static BusinessException notFound(UUID reservationId) {
        return new BusinessException(
                ErrorCode.RESERVATION_NOT_FOUND, "reservation %s does not exist".formatted(reservationId));
    }
}
