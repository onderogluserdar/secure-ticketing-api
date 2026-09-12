package com.onderogluserdar.ticketing.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.event.Event;
import com.onderogluserdar.ticketing.event.EventRepository;
import com.onderogluserdar.ticketing.reservation.ReservationRepository;
import com.onderogluserdar.ticketing.reservation.ReservationService;
import com.onderogluserdar.ticketing.security.CurrentUser;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

class NoOversellPostgresTest extends PostgresTest {

    private static final int CAPACITY = 10;
    private static final int ATTEMPTS = 20;
    private static final int SEATS_EACH = 1;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private EventRepository events;

    @Autowired
    private UserRepository users;

    private enum Outcome {
        RESERVED,
        REFUSED_FOR_CAPACITY
    }

    @Test
    void twentySimultaneousRequestsNeverOversellTenSeats() throws Exception {
        UUID eventId = publishedEventWithCapacity();
        List<CurrentUser> callers = distinctCustomers();

        CountDownLatch ready = new CountDownLatch(ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> unexpected = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (CurrentUser caller : callers) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("workers were never released");
                    }
                    try {
                        reservationService.reserve(eventId, SEATS_EACH, caller);
                        return Outcome.RESERVED;
                    } catch (BusinessException refused) {
                        if (refused.getErrorCode() != ErrorCode.INSUFFICIENT_CAPACITY) {
                            throw refused;
                        }
                        return Outcome.REFUSED_FOR_CAPACITY;
                    }
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all %d workers reached the barrier", ATTEMPTS)
                    .isTrue();
            start.countDown();

            int reserved = 0;
            int refused = 0;
            for (Future<Outcome> future : futures) {
                try {
                    // Bounded, so a locking bug fails the test instead of hanging the build.
                    Outcome outcome = future.get(60, TimeUnit.SECONDS);
                    if (outcome == Outcome.RESERVED) {
                        reserved++;
                    } else {
                        refused++;
                    }
                } catch (Exception failure) {
                    unexpected.add(failure.getCause() == null ? failure : failure.getCause());
                }
            }

            System.out.printf(
                    ">>> attempts=%d reserved=%d refusedForCapacity=%d unexpected=%d%n",
                    ATTEMPTS, reserved, refused, unexpected.size());
            unexpected.forEach(failure -> System.out.println(
                    ">>> UNEXPECTED: " + failure.getClass().getName() + ": " + failure.getMessage()));

            assertThat(unexpected)
                    .as("no deadlocks, lock timeouts or SQL failures")
                    .isEmpty();
            assertThat(reserved).as("successful reservations").isEqualTo(CAPACITY);
            assertThat(refused).as("refusals for capacity").isEqualTo(ATTEMPTS - CAPACITY);
            assertThat(reserved + refused).isEqualTo(ATTEMPTS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("worker pool terminated")
                    .isTrue();
        }

        assertThat(reservations.activeSeatsFor(eventId))
                .as("persisted active seats after every transaction committed")
                .isEqualTo(CAPACITY);
    }

    private UUID publishedEventWithCapacity() {
        UUID ownerId = customer(Role.ORGANIZER).id();
        Event event = Event.createDraft(
                ownerId,
                "Sold Out Show",
                "Ziggo Dome",
                Instant.parse("2026-10-01T18:00:00Z"),
                Instant.parse("2026-10-01T21:00:00Z"),
                CAPACITY);
        event.publish();
        return events.save(event).getId();
    }

    private List<CurrentUser> distinctCustomers() {
        List<CurrentUser> callers = new ArrayList<>();
        for (int i = 0; i < ATTEMPTS; i++) {
            callers.add(customer(Role.CUSTOMER));
        }
        return callers;
    }

    private CurrentUser customer(Role role) {
        User saved =
                users.save(User.create(UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(role), Instant.now()));
        return new CurrentUser(saved.getId(), Set.of(role));
    }
}
