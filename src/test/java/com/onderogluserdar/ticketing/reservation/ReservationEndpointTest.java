package com.onderogluserdar.ticketing.reservation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.event.Event;
import com.onderogluserdar.ticketing.event.EventRepository;
import com.onderogluserdar.ticketing.security.JwtService;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReservationEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private EventRepository events;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private String customerToken;
    private String otherCustomerToken;
    private String adminToken;
    private UUID customerId;
    private UUID publishedEventId;
    private UUID draftEventId;

    @BeforeEach
    void seedEventsAndCustomers() {
        User customer = persistUser(Role.CUSTOMER);
        customerId = customer.getId();
        customerToken = jwtService.issueAccessToken(customer);
        otherCustomerToken = jwtService.issueAccessToken(persistUser(Role.CUSTOMER));
        adminToken = jwtService.issueAccessToken(persistUser(Role.ADMIN));

        UUID organizerId = persistUser(Role.ORGANIZER).getId();
        publishedEventId = persistEvent(organizerId, 10, true);
        draftEventId = persistEvent(organizerId, 10, false);
    }

    @Test
    void reservesThenConfirmsThenCancels() throws Exception {
        UUID reservationId = reserve(publishedEventId, 4, customerToken, status().isCreated());

        mockMvc.perform(authorized(post("/api/reservations/" + reservationId + "/confirm"), customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mockMvc.perform(authorized(post("/api/reservations/" + reservationId + "/cancel"), customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(authorized(post("/api/reservations/" + reservationId + "/cancel"), customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_STATE"));
    }

    @Test
    void refusesToReserveBeyondCapacityAndReleasesTheSeatsOnCancel() throws Exception {
        UUID held = reserve(publishedEventId, 8, customerToken, status().isCreated());

        mockMvc.perform(reservationRequest(publishedEventId, 3, otherCustomerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_CAPACITY"));

        mockMvc.perform(authorized(post("/api/reservations/" + held + "/cancel"), customerToken))
                .andExpect(status().isOk());

        // The cancelled row leaves the active-seat sum, so the same request now fits.
        mockMvc.perform(reservationRequest(publishedEventId, 3, otherCustomerToken))
                .andExpect(status().isCreated());
    }

    @Test
    void refusesToReserveAnUnpublishedEvent() throws Exception {
        mockMvc.perform(reservationRequest(draftEventId, 1, customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_PUBLISHED"));
    }

    @Test
    void refusesToLetAnotherCustomerActOnTheReservationButLetsAnAdmin() throws Exception {
        UUID reservationId = reserve(publishedEventId, 2, customerToken, status().isCreated());

        mockMvc.perform(authorized(post("/api/reservations/" + reservationId + "/confirm"), otherCustomerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RESERVATION_ACCESS_DENIED"));

        mockMvc.perform(authorized(post("/api/reservations/" + reservationId + "/cancel"), otherCustomerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RESERVATION_ACCESS_DENIED"));

        mockMvc.perform(authorized(post("/api/reservations/" + reservationId + "/confirm"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(customerId.toString()));
    }

    @Test
    void requiresTheIdempotencyKeyHeader() throws Exception {
        mockMvc.perform(authorized(post("/api/events/" + publishedEventId + "/reservations"), customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":1}"))
                .andExpect(status().isBadRequest());
    }

    private UUID reserve(
            UUID eventId, int seats, String token, org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        String body = mockMvc.perform(reservationRequest(eventId, seats, token))
                .andExpect(expected)
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private MockHttpServletRequestBuilder reservationRequest(UUID eventId, int seats, String token) {
        return authorized(post("/api/events/" + eventId + "/reservations"), token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":%d}".formatted(seats));
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User persistUser(Role role) {
        return users.saveAndFlush(
                User.create(UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(role), Instant.now()));
    }

    private UUID persistEvent(UUID ownerId, int capacity, boolean published) {
        Event event = Event.createDraft(
                ownerId,
                "Concert",
                "Ziggo Dome",
                Instant.parse("2026-10-01T18:00:00Z"),
                Instant.parse("2026-10-01T21:00:00Z"),
                capacity);
        if (published) {
            event.publish();
        }
        return events.saveAndFlush(event).getId();
    }
}
