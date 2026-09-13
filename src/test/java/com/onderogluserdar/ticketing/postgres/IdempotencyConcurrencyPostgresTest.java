package com.onderogluserdar.ticketing.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.springframework.jdbc.core.simple.JdbcClient;

import com.onderogluserdar.ticketing.event.Event;
import com.onderogluserdar.ticketing.event.EventRepository;
import com.onderogluserdar.ticketing.reservation.IdempotentReservationService;
import com.onderogluserdar.ticketing.security.CurrentUser;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Capacity is far above the attempt count on purpose: if capacity were the binding constraint the
 * Event lock could make this pass without idempotency doing anything.
 */
class IdempotencyConcurrencyPostgresTest extends PostgresTest {

    private static final int ATTEMPTS = 20;
    private static final int CAPACITY = 50;
    private static final int SEATS = 2;
    private static final String KEY = "one-key-many-retries";

    @Autowired
    private IdempotentReservationService idempotentReservations;

    @Autowired
    private EventRepository events;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meters;

    @Test
    void twentySimultaneousDuplicatesCreateExactlyOneReservation() throws Exception {
        jdbc.sql("delete from idempotency_keys").update();
        User customer = users.save(
                User.create(UUID.randomUUID() + "@example.com", "$2a$12$h", Set.of(Role.CUSTOMER), Instant.now()));
        CurrentUser caller = new CurrentUser(customer.getId(), Set.of(Role.CUSTOMER));
        UUID eventId = publishedEvent();
        double createdBefore = counter("ticketing.reservation.created");
        double replaysBefore = counter("ticketing.idempotency.replay");

        CountDownLatch ready = new CountDownLatch(ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> unexpected = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        List<IdempotentReservationService.Outcome> outcomes = new ArrayList<>();
        try {
            List<Future<IdempotentReservationService.Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < ATTEMPTS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("workers were never released");
                    }
                    return idempotentReservations.reserve(KEY, eventId, SEATS, caller);
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all %d workers reached the barrier", ATTEMPTS)
                    .isTrue();
            start.countDown();

            for (Future<IdempotentReservationService.Outcome> future : futures) {
                try {
                    outcomes.add(future.get(60, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    unexpected.add(failure.getCause() == null ? failure : failure.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("worker pool terminated")
                    .isTrue();
        }

        long rows = jdbc.sql("select count(*) from reservations where event_id = ?")
                .param(eventId)
                .query(Long.class)
                .single();
        Set<UUID> distinctReservations =
                outcomes.stream().map(outcome -> outcome.response().id()).collect(HashSet::new, Set::add, Set::addAll);

        System.out.printf(
                ">>> attempts=%d answered=%d unexpected=%d reservationRows=%d distinctIds=%d%n",
                ATTEMPTS, outcomes.size(), unexpected.size(), rows, distinctReservations.size());
        unexpected.forEach(failure ->
                System.out.println(">>> UNEXPECTED: " + failure.getClass().getName() + ": " + failure.getMessage()));

        assertThat(unexpected).as("no deadlocks, lock timeouts or SQL failures").isEmpty();
        assertThat(outcomes).as("every caller got an answer").hasSize(ATTEMPTS);
        assertThat(rows).as("exactly one reservation row").isEqualTo(1);
        assertThat(distinctReservations)
                .as("every answer names the same reservation")
                .hasSize(1);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(201));
        assertThat(jdbc.sql("select count(*) from idempotency_keys where idempotency_key = ?")
                        .param(KEY)
                        .query(Long.class)
                        .single())
                .as("one key row owns the scope")
                .isEqualTo(1);

        // The losers of the unique-key race are replays, not creations, even under real contention.
        assertThat(counter("ticketing.reservation.created") - createdBefore)
                .as("one commit, one count")
                .isEqualTo(1);
        assertThat(counter("ticketing.idempotency.replay") - replaysBefore)
                .as("every other caller was answered from the stored result")
                .isEqualTo(ATTEMPTS - 1);
    }

    private double counter(String name) {
        return meters.get(name).counter().count();
    }

    private UUID publishedEvent() {
        User organizer = users.save(
                User.create(UUID.randomUUID() + "@example.com", "$2a$12$h", Set.of(Role.ORGANIZER), Instant.now()));
        Event event = Event.createDraft(
                organizer.getId(),
                "Big Venue",
                "Ziggo Dome",
                Instant.parse("2026-10-01T18:00:00Z"),
                Instant.parse("2026-10-01T21:00:00Z"),
                CAPACITY);
        event.publish();
        return events.save(event).getId();
    }
}
