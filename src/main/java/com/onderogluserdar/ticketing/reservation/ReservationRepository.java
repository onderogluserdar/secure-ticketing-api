package com.onderogluserdar.ticketing.reservation;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForStateChange(@Param("id") UUID id);

    @Query("""
            select coalesce(sum(r.seats), 0) from Reservation r
            where r.eventId = :eventId and r.status <> ReservationStatus.CANCELLED
            """)
    int activeSeatsFor(@Param("eventId") UUID eventId);
}
