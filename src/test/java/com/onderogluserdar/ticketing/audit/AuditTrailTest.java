package com.onderogluserdar.ticketing.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;

import com.onderogluserdar.ticketing.event.Event;
import com.onderogluserdar.ticketing.event.EventRepository;
import com.onderogluserdar.ticketing.security.JwtService;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

/** Not transactional: audit rows have to be observed after the business transaction commits. */
@SpringBootTest
@AutoConfigureMockMvc
class AuditTrailTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private UserRepository users;

    @Autowired
    private EventRepository events;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    @BeforeEach
    void clearCommittedRows() {
        jdbc.sql("delete from audit_logs").update();
        jdbc.sql("delete from idempotency_keys").update();
        jdbc.sql("delete from reservations").update();
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from user_roles").update();
        jdbc.sql("delete from users").update();
    }

    @Test
    void recordsAReservationCreationWithItsActorResourceAndRequestMetadata() throws Exception {
        User customer = persistUser(Role.CUSTOMER);
        UUID eventId = persistPublishedEvent();

        mockMvc.perform(post("/api/events/" + eventId + "/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issueAccessToken(customer))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header(HttpHeaders.USER_AGENT, "probe-agent/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":2}"))
                .andExpect(status().isCreated());

        List<AuditLog> recorded = auditLogs.findByActionOrderByCreatedAtAsc(AuditAction.RESERVATION_CREATED);

        assertThat(recorded).hasSize(1);
        AuditLog entry = recorded.getFirst();
        assertThat(entry.getActorId()).isEqualTo(customer.getId());
        assertThat(entry.getResourceType()).isEqualTo("Reservation");
        assertThat(entry.getResourceId()).isNotBlank();
        assertThat(entry.getUserAgent()).isEqualTo("probe-agent/1.0");
        assertThat(entry.getIp()).isNotBlank();
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    void recordsAFailedLoginEvenThoughItsTransactionRollsBack() throws Exception {
        persistRegisteredUser("audit-login@example.com");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"audit-login@example.com\",\"password\":\"wrong password entirely\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(auditLogs.findByActionOrderByCreatedAtAsc(AuditAction.LOGIN_FAILURE))
                .hasSize(1)
                .allSatisfy(entry -> assertThat(entry.getActorId()).isNull());
    }

    @Test
    void writesNoAuditRowWhenTheBusinessOperationIsRejected() throws Exception {
        User customer = persistUser(Role.CUSTOMER);
        UUID eventId = persistPublishedEvent();

        mockMvc.perform(post("/api/events/" + eventId + "/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issueAccessToken(customer))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":99}"))
                .andExpect(status().isConflict());

        assertThat(auditLogs.findByActionOrderByCreatedAtAsc(AuditAction.RESERVATION_CREATED))
                .isEmpty();
    }

    @Test
    void storesNoCredentialTokenOrRequestBodyMaterial() throws Exception {
        User customer = persistRegisteredUser("audit-clean@example.com");
        String token = jwtService.issueAccessToken(customer);
        UUID eventId = persistPublishedEvent();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"audit-clean@example.com\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/events/" + eventId + "/reservations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", "key-that-should-not-be-stored")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":1}"))
                .andExpect(status().isCreated());

        String everything =
                auditLogs.findAll().stream().map(AuditTrailTest::flatten).reduce("", String::concat);

        assertThat(auditLogs.findAll()).isNotEmpty();
        assertThat(everything)
                .doesNotContain(PASSWORD)
                .doesNotContain(token)
                .doesNotContain("Bearer ")
                .doesNotContain("$2a$")
                .doesNotContain("key-that-should-not-be-stored")
                .doesNotContain("seats");
    }

    private static String flatten(AuditLog entry) {
        return "%s|%s|%s|%s|%s|%s"
                .formatted(
                        entry.getAction(),
                        entry.getActorId(),
                        entry.getResourceType(),
                        entry.getResourceId(),
                        entry.getIp(),
                        entry.getUserAgent());
    }

    private User persistUser(Role role) {
        return users.save(User.create(UUID.randomUUID() + "@example.com", "$2a$12$hash", Set.of(role), Instant.now()));
    }

    private User persistRegisteredUser(String email) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        return users.findByEmail(email).orElseThrow();
    }

    private UUID persistPublishedEvent() {
        Event event = Event.createDraft(
                persistUser(Role.ORGANIZER).getId(),
                "Concert",
                "Ziggo Dome",
                Instant.parse("2026-10-01T18:00:00Z"),
                Instant.parse("2026-10-01T21:00:00Z"),
                10);
        event.publish();
        return events.save(event).getId();
    }
}
