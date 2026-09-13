package com.onderogluserdar.ticketing.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * Springdoc builds the document by reflecting over the live Spring MVC mappings, so a version
 * mismatch with the Boot line fails at request time rather than at dependency resolution. This
 * asserts the contract a reviewer actually reads, not a full document snapshot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("OpenAPI document")
class OpenApiDocumentTest {

    private static final String RESERVE = "/api/events/{eventId}/reservations";
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> document;

    @BeforeAll
    void generateTheDocument() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        document = objectMapper.readValue(json, Map.class);
    }

    @Test
    @DisplayName("is reachable anonymously so a reviewer can read it")
    void staysPubliclyReadable() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("documents every application endpoint, eleven operations over ten paths")
    void documentsEveryEndpoint() {
        Map<String, Object> paths = child(document, "paths");

        assertThat(paths.keySet())
                .containsExactlyInAnyOrder(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/api/events",
                        "/api/events/{id}",
                        "/api/events/{id}/publish",
                        "/api/events/public",
                        RESERVE,
                        "/api/reservations/{id}/confirm",
                        "/api/reservations/{id}/cancel");

        // /api/events carries both create and list, which is why ten paths hold eleven operations.
        long operations = paths.values().stream()
                .flatMap(path -> asMap(path).keySet().stream())
                .filter(HTTP_METHODS::contains)
                .count();
        assertThat(operations).isEqualTo(11);
    }

    @Test
    @DisplayName("declares the bearer JWT scheme and applies it to protected operations only")
    void declaresTheSecurityScheme() {
        Map<String, Object> scheme = child(child(child(document, "components"), "securitySchemes"), "bearerAuth");
        assertThat(scheme).containsEntry("type", "http").containsEntry("scheme", "bearer");
        assertThat(scheme).containsEntry("bearerFormat", "JWT");

        // The requirement is declared once at the root and inherited by every operation.
        assertThat(document.get("security").toString()).contains("bearerAuth");
        assertThat(operation("/api/events", "post")).doesNotContainKey("security");

        // An empty list is how OpenAPI says "no credential needed", so it has to be present.
        assertThat(operation("/api/auth/login", "post")).containsEntry("security", List.of());
        assertThat(operation("/api/events/public", "get")).containsEntry("security", List.of());
    }

    @Test
    @DisplayName("documents Idempotency-Key as a required header on reservation creation")
    void documentsTheIdempotencyHeader() {
        Map<String, Object> header = parameters(operation(RESERVE, "post")).stream()
                .map(OpenApiDocumentTest::asMap)
                .filter(parameter -> "Idempotency-Key".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Idempotency-Key is not documented"));

        assertThat(header).containsEntry("in", "header").containsEntry("required", true);
    }

    /** A contract that only documents the happy path tells a client nothing useful about failure. */
    @Test
    @DisplayName("documents the failure contracts a client has to handle")
    void documentsTheFailureContracts() {
        assertThat(child(operation("/api/auth/login", "post"), "responses").keySet())
                .contains("200", "401", "429");
        assertThat(child(operation(RESERVE, "post"), "responses").keySet()).contains("201", "400", "401", "403", "409");

        // A 429 without Retry-After leaves the client guessing how long to back off.
        Map<String, Object> retryAfter = child(
                child(child(child(operation("/api/auth/login", "post"), "responses"), "429"), "headers"),
                "Retry-After");
        assertThat(retryAfter).isNotEmpty();
        assertThat(child(retryAfter, "schema")).containsEntry("type", "integer");
    }

    @Test
    @DisplayName("documents request and response models rather than bare status codes")
    void documentsTheModels() {
        Map<String, Object> loginSchema = child(
                child(child(child(operation("/api/auth/login", "post"), "requestBody"), "content"), "application/json"),
                "schema");
        assertThat(loginSchema.get("$ref").toString()).endsWith("LoginRequest");

        assertThat(child(child(child(document, "components"), "schemas"), "ReservationResponse"))
                .isNotEmpty();
        assertThat(child(child(child(child(document, "components"), "schemas"), "ReservationResponse"), "properties")
                        .keySet())
                .contains("id", "eventId", "seats", "status");

        // A password hash must never reach a published schema.
        assertThat(child(child(document, "components"), "schemas").toString()).doesNotContain("passwordHash");
    }

    private Map<String, Object> operation(String path, String method) {
        return child(child(document, "paths"), path).isEmpty()
                ? Map.of()
                : asMap(child(child(document, "paths"), path).get(method));
    }

    private static List<?> parameters(Map<String, Object> operation) {
        return (List<?>) operation.get("parameters");
    }

    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        return asMap(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
