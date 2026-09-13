package com.onderogluserdar.ticketing.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.security.JwtService;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

/** Real access tokens rather than mock authentication, so the resource-server path is included. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Actuator exposure")
class ActuatorExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PathMappedEndpoints exposedEndpoints;

    private String adminToken;
    private String organizerToken;

    @BeforeEach
    void issueTokens() {
        adminToken = tokenFor(Role.ADMIN);
        organizerToken = tokenFor(Role.ORGANIZER);
    }

    @Test
    @DisplayName("answers a liveness check to anyone, and says nothing but the status")
    void exposesHealthPublicly() throws Exception {
        String body = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // A public probe must not enumerate databases, disk paths or connection state.
        assertThat(body).doesNotContain("components", "db", "diskSpace", "ping", "validationQuery");
    }

    @Test
    @DisplayName("refuses anonymous access to the diagnostic endpoints")
    void protectsEverythingExceptHealth() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics/ticketing.reservation.created")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("hides diagnostics from an authenticated non-admin")
    void keepsDiagnosticsAdminOnly() throws Exception {
        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, "Bearer " + organizerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/info").header(HttpHeaders.AUTHORIZATION, "Bearer " + organizerToken))
                .andExpect(status().isForbidden());
    }

    /** Requires the build-info goal, so this runs under Maven rather than a bare IDE test run. */
    @Test
    @DisplayName("gives an admin real build metadata rather than an empty document")
    void exposesBuildInfoToAdmin() throws Exception {
        mockMvc.perform(get("/actuator/info").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.artifact").value("secure-ticketing-api"))
                .andExpect(jsonPath("$.build.group").value("com.onderogluserdar"))
                .andExpect(jsonPath("$.build.version").exists())
                // Excluded on purpose: without a reproducible-build timestamp it would change per build.
                .andExpect(jsonPath("$.build.time").doesNotExist());
    }

    @Test
    @DisplayName("lets an admin read the custom counters")
    void allowsAdminToReadMetrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names")
                        .value(hasItems(
                                "ticketing.reservation.created",
                                "ticketing.reservation.rejected.capacity",
                                "ticketing.idempotency.replay",
                                "ticketing.rate.limit.rejected")));
    }

    /** Nothing else is reachable at all: env, beans, configprops and the rest stay unmapped. */
    @Test
    @DisplayName("maps exactly the three endpoints we opted into and nothing else")
    void exposesOnlyTheThreeChosenEndpoints() {
        assertThat(exposedEndpoints.getAllPaths())
                .containsExactlyInAnyOrder("/actuator/health", "/actuator/info", "/actuator/metrics");
    }

    private String tokenFor(Role role) {
        return jwtService.issueAccessToken(users.save(
                User.create(UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(role), Instant.now())));
    }
}
