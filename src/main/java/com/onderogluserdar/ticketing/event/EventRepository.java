package com.onderogluserdar.ticketing.event;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

    /** Every decision about available capacity serialises on this row lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findByIdForCapacityChange(@Param("id") UUID id);

    Page<Event> findAllByOwnerId(UUID ownerId, Pageable pageable);

    @Query("""
            select e from Event e
            where e.published = true
              and e.endsAt >= coalesce(:from, e.endsAt)
              and e.startsAt <= coalesce(:to, e.startsAt)
              and (lower(e.title) like lower(concat('%', coalesce(:q, ''), '%'))
                   or lower(e.venue) like lower(concat('%', coalesce(:q, ''), '%')))
            """)
    Page<Event> findPublished(
            @Param("from") Instant from, @Param("to") Instant to, @Param("q") String q, Pageable pageable);
}
