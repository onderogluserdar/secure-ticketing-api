package com.onderogluserdar.ticketing.event;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.onderogluserdar.ticketing.reservation.Reservation;
import com.onderogluserdar.ticketing.reservation.ReservationRepository;
import com.onderogluserdar.ticketing.security.JwtService;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventEndpointTest {

    private static final String CONCERT = """
            {"title":"Concert","venue":"Ataturk Kultur Merkezi",
             "startsAt":"2026-10-01T18:00:00Z","endsAt":"2026-10-01T21:00:00Z","capacity":100}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private EventRepository events;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private String organizerToken;
    private String otherOrganizerToken;
    private String adminToken;
    private String customerToken;
    private UUID organizerId;

    @BeforeEach
    void createAccounts() {
        User organizer = persist("organizer", Role.ORGANIZER);
        organizerId = organizer.getId();
        organizerToken = jwtService.issueAccessToken(organizer);
        otherOrganizerToken = jwtService.issueAccessToken(persist("other-organizer", Role.ORGANIZER));
        adminToken = jwtService.issueAccessToken(persist("admin", Role.ADMIN));
        customerToken = jwtService.issueAccessToken(persist("customer", Role.CUSTOMER));
    }

    @Test
    void createsUpdatesAndPublishesAnEvent() throws Exception {
        UUID eventId = createConcert(organizerToken);

        mockMvc.perform(authorized(put("/api/events/" + eventId), organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONCERT.replace("\"capacity\":100", "\"capacity\":250")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(250))
                .andExpect(jsonPath("$.published").value(false));

        mockMvc.perform(authorized(post("/api/events/" + eventId + "/publish"), organizerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));

        mockMvc.perform(authorized(post("/api/events/" + eventId + "/publish"), organizerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_PUBLISHED"));
    }

    @Test
    void refusesToLetAnotherOrganizerTouchTheEventButLetsAnAdmin() throws Exception {
        UUID eventId = createConcert(organizerToken);

        mockMvc.perform(authorized(put("/api/events/" + eventId), otherOrganizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONCERT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVENT_ACCESS_DENIED"));

        mockMvc.perform(authorized(post("/api/events/" + eventId + "/publish"), otherOrganizerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVENT_ACCESS_DENIED"));

        mockMvc.perform(authorized(post("/api/events/" + eventId + "/publish"), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(organizerId.toString()));
    }

    @Test
    void refusesEventCreationForACustomer() throws Exception {
        mockMvc.perform(authorized(post("/api/events"), customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONCERT))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesToShrinkCapacityBelowTheSeatsAlreadyReserved() throws Exception {
        UUID eventId = createConcert(organizerToken);
        reservations.saveAndFlush(Reservation.createPending(eventId, organizerId, 40, Instant.now()));

        mockMvc.perform(authorized(put("/api/events/" + eventId), organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONCERT.replace("\"capacity\":100", "\"capacity\":39")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_BELOW_RESERVED"));

        mockMvc.perform(authorized(put("/api/events/" + eventId), organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONCERT.replace("\"capacity\":100", "\"capacity\":40")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(40));
    }

    @Test
    void reportsAnEventThatDoesNotExist() throws Exception {
        mockMvc.perform(authorized(post("/api/events/" + UUID.randomUUID() + "/publish"), organizerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    private UUID createConcert(String token) throws Exception {
        String body = mockMvc.perform(authorized(post("/api/events"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONCERT))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User persist(String localPart, Role role) {
        return users.saveAndFlush(User.create(
                localPart + "-" + UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(role), Instant.now()));
    }
}
