package com.onderogluserdar.ticketing.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.onderogluserdar.ticketing.event.Event;
import com.onderogluserdar.ticketing.event.EventRepository;
import com.onderogluserdar.ticketing.reservation.IdempotentReservationService;
import com.onderogluserdar.ticketing.reservation.ReservationService;
import com.onderogluserdar.ticketing.security.CurrentUser;
import com.onderogluserdar.ticketing.security.JwtService;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The registry is shared with every other test in the context, so every assertion is a delta
 * measured around a single request rather than an absolute count.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Reservation counters")
class ReservationMetricsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private UserRepository users;

    @Autowired
    private EventRepository events;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private IdempotentReservationService idempotentReservations;

    @MockitoSpyBean
    private ReservationService reservationService;

    private String customerToken;
    private CurrentUser caller;

    @AfterEach
    void clearCommittedRows() {
        jdbc.sql("delete from idempotency_keys").update();
        jdbc.sql("delete from reservations").update();
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from user_roles").update();
        jdbc.sql("delete from users").update();
    }

    @BeforeEach
    void seed() {
        clearCommittedRows();
        User customer = persistUser(Role.CUSTOMER);
        customerToken = jwtService.issueAccessToken(customer);
        caller = new CurrentUser(customer.getId(), Set.of(Role.CUSTOMER));
    }

    @Test
    @DisplayName("counts a genuinely created reservation once")
    void countsACreatedReservation() throws Exception {
        UUID eventId = persistPublishedEvent(10);
        double before = count("ticketing.reservation.created");

        mockMvc.perform(reservation(eventId, 2, "key-created")).andExpect(status().isCreated());

        assertThat(count("ticketing.reservation.created") - before).isEqualTo(1);
    }

    /** The whole point of the separation: a replay is a repeated answer, not a new reservation. */
    @Test
    @DisplayName("counts a replay as a replay and not as a creation")
    void doesNotCountAReplayAsACreation() throws Exception {
        UUID eventId = persistPublishedEvent(10);
        mockMvc.perform(reservation(eventId, 2, "key-replay")).andExpect(status().isCreated());

        double created = count("ticketing.reservation.created");
        double replays = count("ticketing.idempotency.replay");

        mockMvc.perform(reservation(eventId, 2, "key-replay")).andExpect(status().isCreated());

        assertThat(count("ticketing.reservation.created") - created).isZero();
        assertThat(count("ticketing.idempotency.replay") - replays).isEqualTo(1);
    }

    @Test
    @DisplayName("counts a capacity refusal without counting a creation")
    void countsACapacityRejection() throws Exception {
        UUID eventId = persistPublishedEvent(2);
        double rejections = count("ticketing.reservation.rejected.capacity");
        double created = count("ticketing.reservation.created");

        mockMvc.perform(reservation(eventId, 3, "key-too-big"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"));

        assertThat(count("ticketing.reservation.rejected.capacity") - rejections)
                .isEqualTo(1);
        assertThat(count("ticketing.reservation.created") - created).isZero();
    }

    /**
     * The reason the counter lives in the orchestrator rather than in {@code reserve()}: the
     * reservation is created inside the transaction, then the transaction fails, so nothing is
     * persisted. Counting before the commit would report a reservation that does not exist.
     */
    @Test
    @DisplayName("does not count a reservation whose transaction rolls back after creating it")
    void doesNotCountACreationThatNeverCommits() {
        UUID eventId = persistPublishedEvent(10);
        double created = count("ticketing.reservation.created");

        doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new IllegalStateException("failing after the reservation row exists");
                })
                .when(reservationService)
                .reserve(any(), anyInt(), any());

        assertThatThrownBy(() -> idempotentReservations.reserve("key-rollback", eventId, 2, caller))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("ticketing.reservation.created") - created).isZero();
        assertThat(reservationCount())
                .as("the rollback must leave no reservation behind")
                .isZero();
    }

    /** A conflict is a different outcome from a replay and must not inflate the replay count. */
    @Test
    @DisplayName("does not count an idempotency conflict as a replay")
    void doesNotCountAConflictAsAReplay() throws Exception {
        UUID eventId = persistPublishedEvent(10);
        mockMvc.perform(reservation(eventId, 2, "key-conflict")).andExpect(status().isCreated());

        double replays = count("ticketing.idempotency.replay");

        mockMvc.perform(reservation(eventId, 5, "key-conflict")).andExpect(status().isConflict());

        assertThat(count("ticketing.idempotency.replay") - replays).isZero();
    }

    @Test
    @DisplayName("carries no high-cardinality tags")
    void keepsTheCountersUntagged() {
        for (String name : Set.of(
                "ticketing.reservation.created",
                "ticketing.reservation.rejected.capacity",
                "ticketing.idempotency.replay",
                "ticketing.rate.limit.rejected")) {
            assertThat(registry.get(name).counter().getId().getTags())
                    .as("%s must stay aggregate-only", name)
                    .isEmpty();
        }
    }

    private long reservationCount() {
        return jdbc.sql("select count(*) from reservations").query(Long.class).single();
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    private MockHttpServletRequestBuilder reservation(UUID eventId, int seats, String key) {
        return post("/api/events/" + eventId + "/reservations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":%d}".formatted(seats));
    }

    private User persistUser(Role role) {
        return users.save(User.create(UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(role), Instant.now()));
    }

    private UUID persistPublishedEvent(int capacity) {
        Event event = Event.createDraft(
                persistUser(Role.ORGANIZER).getId(),
                "Concert",
                "Ziggo Dome",
                Instant.parse("2026-10-01T18:00:00Z"),
                Instant.parse("2026-10-01T21:00:00Z"),
                capacity);
        event.publish();
        return events.save(event).getId();
    }
}
