package com.onderogluserdar.ticketing.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.event.EventRepository;
import com.onderogluserdar.ticketing.security.JwtService;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ErrorContractTest {

    private static final String VALID_EVENT = """
            {"title":"Concert","venue":"Ziggo Dome","startsAt":"2026-10-01T18:00:00Z",
             "endsAt":"2026-10-01T21:00:00Z","capacity":10}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private EventRepository events;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwtService;

    private String organizerToken;
    private String customerToken;

    @BeforeEach
    void issueTokens() {
        organizerToken = jwtService.issueAccessToken(persistUser(Role.ORGANIZER));
        customerToken = jwtService.issueAccessToken(persistUser(Role.CUSTOMER));
    }

    @Test
    void reportsFieldLevelValidationFailuresWithoutEchoingValues() throws Exception {
        String blankTitleAndNoCapacity = """
                {"title":"  ","venue":"Ziggo Dome","startsAt":"2026-10-01T18:00:00Z",
                 "endsAt":"2026-10-01T21:00:00Z","capacity":0}""";

        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankTitleAndNoCapacity))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.capacity").exists())
                .andExpect(jsonPath("$.errors.venue").doesNotExist());
    }

    @Test
    void reportsAMalformedBodyAsAnInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void reportsAnUnparseablePathVariableAsAnInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/reservations/not-a-uuid/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void answersAMissingTokenWithAProblemDetailAndABearerChallenge() throws Exception {
        mockMvc.perform(get("/api/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                // The filter chain writes its own body, so the media type is asserted here too.
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void answersAForbiddenRoleWithAProblemDetail() throws Exception {
        mockMvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void revealsNothingInternalWhenSomethingUnexpectedFails() throws Exception {
        doThrow(new DataIntegrityViolationException("ERROR: violates unique constraint \"events_pk\"; "
                        + "SQL [insert into events (capacity,ends_at,owner_id) values (?,?,?)]"))
                .when(events)
                .save(any());

        String body = mockMvc.perform(post("/api/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + organizerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("SQL")
                .doesNotContain("insert into")
                .doesNotContain("constraint")
                .doesNotContain("DataIntegrityViolation")
                .doesNotContain("com.onderogluserdar");
    }

    private User persistUser(Role role) {
        return users.saveAndFlush(
                User.create(UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(role), Instant.now()));
    }
}
