package com.onderogluserdar.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.onderogluserdar.ticketing.event.Event;
import com.onderogluserdar.ticketing.event.EventRepository;
import com.onderogluserdar.ticketing.security.JwtService;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotentReservationTest {

    private static final String KEY = "shared-key-42";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private EventRepository events;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JwtService jwtService;

    private String customerToken;
    private String otherCustomerToken;
    private UUID eventId;

    /**
     * Not {@code @Transactional}: replay has to be proven across committed transactions, the way
     * two HTTP requests actually behave. Committed rows therefore have to be cleaned up by hand.
     */
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
        customerToken = jwtService.issueAccessToken(persistUser(Role.CUSTOMER));
        otherCustomerToken = jwtService.issueAccessToken(persistUser(Role.CUSTOMER));
        eventId = persistPublishedEvent();
    }

    /** The stored body is serialized JSON, so a replay must not come back as escaped JSON or text. */
    @Test
    void replaysTheOriginalResultForTheSameKeyAndPayload() throws Exception {
        MockHttpServletResponse original = mockMvc.perform(reservation(2, KEY, customerToken))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn()
                .getResponse();

        MockHttpServletResponse replay = mockMvc.perform(reservation(2, KEY, customerToken))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.seats").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn()
                .getResponse();

        assertThat(replay.getContentAsString()).isEqualTo(original.getContentAsString());
        assertThat(replay.getContentType()).isEqualTo(original.getContentType());
        // A doubly-encoded body would arrive as a quoted string with escaped quotes inside.
        assertThat(replay.getContentAsString()).startsWith("{").doesNotContain("\\\"");
        assertThat(reservationCount()).isEqualTo(1);
    }

    @Test
    void rejectsTheSameKeyWithADifferentPayload() throws Exception {
        mockMvc.perform(reservation(2, KEY, customerToken)).andExpect(status().isCreated());

        mockMvc.perform(reservation(3, KEY, customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        assertThat(reservationCount()).isEqualTo(1);
    }

    @Test
    void scopesTheKeyToItsOwnPrincipal() throws Exception {
        mockMvc.perform(reservation(2, KEY, customerToken)).andExpect(status().isCreated());

        // Same key, same payload, different caller: an independent scope, not a replay.
        mockMvc.perform(reservation(2, KEY, otherCustomerToken)).andExpect(status().isCreated());

        assertThat(reservationCount()).isEqualTo(2);
    }

    @Test
    void letsAnExpiredKeyBeReusedEvenThoughItStillOccupiesTheUniqueScope() throws Exception {
        mockMvc.perform(reservation(2, KEY, customerToken)).andExpect(status().isCreated());

        jdbc.sql("update idempotency_keys set expires_at = ? where idempotency_key = ?")
                .params(Instant.now().minusSeconds(60), KEY)
                .update();

        mockMvc.perform(reservation(2, KEY, customerToken)).andExpect(status().isCreated());

        assertThat(reservationCount()).isEqualTo(2);
        assertThat(keyCount()).isEqualTo(1);
    }

    @Test
    void refusesABlankIdempotencyKey() throws Exception {
        mockMvc.perform(reservation(2, "   ", customerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(reservationCount()).isZero();
    }

    /** Rejected here rather than becoming a 500 when PostgreSQL refuses the oversized column. */
    @Test
    void refusesAnIdempotencyKeyLongerThanTheColumn() throws Exception {
        mockMvc.perform(reservation(2, "k".repeat(201), customerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(reservationCount()).isZero();
        mockMvc.perform(reservation(2, "k".repeat(200), customerToken)).andExpect(status().isCreated());
    }

    private MockHttpServletRequestBuilder reservation(int seats, String key, String token) {
        return post("/api/events/" + eventId + "/reservations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":%d}".formatted(seats));
    }

    private long reservationCount() {
        return jdbc.sql("select count(*) from reservations where event_id = ?")
                .param(eventId)
                .query(Long.class)
                .single();
    }

    private long keyCount() {
        return jdbc.sql("select count(*) from idempotency_keys where idempotency_key = ?")
                .param(KEY)
                .query(Long.class)
                .single();
    }

    private User persistUser(Role role) {
        return users.save(User.create(UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(role), Instant.now()));
    }

    private UUID persistPublishedEvent() {
        Event event = Event.createDraft(
                persistUser(Role.ORGANIZER).getId(),
                "Concert",
                "Ziggo Dome",
                Instant.parse("2026-10-01T18:00:00Z"),
                Instant.parse("2026-10-01T21:00:00Z"),
                50);
        event.publish();
        return events.save(event).getId();
    }
}
