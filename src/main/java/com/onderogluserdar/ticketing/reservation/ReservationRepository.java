package com.onderogluserdar.ticketing.reservation;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @Query("""
            select coalesce(sum(r.seats), 0) from Reservation r
            where r.eventId = :eventId and r.status <> ReservationStatus.CANCELLED
            """)
    int activeSeatsFor(@Param("eventId") UUID eventId);
}
