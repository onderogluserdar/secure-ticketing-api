package com.onderogluserdar.ticketing.event;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.security.JwtService;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventListingTest {

    private static final Instant OCTOBER_START = Instant.parse("2026-10-01T18:00:00Z");
    private static final Instant OCTOBER_END = Instant.parse("2026-10-01T21:00:00Z");
    private static final Instant DECEMBER_START = Instant.parse("2026-12-24T18:00:00Z");
    private static final Instant DECEMBER_END = Instant.parse("2026-12-24T21:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private EventRepository events;

    @Autowired
    private JwtService jwtService;

    private UUID organizerId;
    private UUID otherOrganizerId;
    private String organizerToken;
    private String otherOrganizerToken;
    private String adminToken;

    @BeforeEach
    void seedTwoOrganizersWithEvents() {
        User organizer = persistUser(Role.ORGANIZER);
        User otherOrganizer = persistUser(Role.ORGANIZER);
        organizerId = organizer.getId();
        otherOrganizerId = otherOrganizer.getId();
        organizerToken = jwtService.issueAccessToken(organizer);
        otherOrganizerToken = jwtService.issueAccessToken(otherOrganizer);
        adminToken = jwtService.issueAccessToken(persistUser(Role.ADMIN));

        persistEvent(organizerId, "Concert", "Ziggo Dome", OCTOBER_START, OCTOBER_END, true);
        persistEvent(organizerId, "Draft Show", "Paradiso", DECEMBER_START, DECEMBER_END, false);
        persistEvent(otherOrganizerId, "Rival Gig", "Melkweg", OCTOBER_START, OCTOBER_END, true);
    }

    @Test
    void confinesAnOrganizerToTheirOwnEventsAndRefusesAnotherOwnerExplicitly() throws Exception {
        mockMvc.perform(authorized(get("/api/events"), organizerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].ownerId")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo(organizerId.toString()))));

        mockMvc.perform(authorized(get("/api/events?ownerId=" + organizerId), organizerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(authorized(get("/api/events?ownerId=" + otherOrganizerId), organizerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVENT_ACCESS_DENIED"));

        mockMvc.perform(authorized(get("/api/events?ownerId=" + otherOrganizerId), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Rival Gig"));
    }

    @Test
    void publishesOnlyPublishedEventsToAnonymousDiscoveryAndFiltersThem() throws Exception {
        mockMvc.perform(get("/api/events/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].title")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Draft Show"))));

        mockMvc.perform(get("/api/events/public?q=ZIGGO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Concert"));

        mockMvc.perform(get("/api/events/public?from=2026-11-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/events/public?from=2026-10-01T19:00:00Z&to=2026-10-01T20:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/events/public?from=2026-12-01T00:00:00Z&to=2026-10-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsAPageSizeBeyondTheCap() throws Exception {
        mockMvc.perform(get("/api/events/public?size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User persistUser(Role role) {
        return users.saveAndFlush(
                User.create(UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(role), Instant.now()));
    }

    private void persistEvent(
            UUID ownerId, String title, String venue, Instant startsAt, Instant endsAt, boolean published) {
        Event event = Event.createDraft(ownerId, title, venue, startsAt, endsAt, 100);
        if (published) {
            event.publish();
        }
        events.saveAndFlush(event);
    }
}
