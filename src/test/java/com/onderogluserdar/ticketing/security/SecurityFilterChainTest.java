package com.onderogluserdar.ticketing.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;

/** Uses a real servlet container: MockMvc skips the error dispatch this chain has to survive. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityFilterChainTest {

    /** Protected by {@code anyRequest().authenticated()}; the chain answers before routing does. */
    private static final String PROTECTED_PATH = "/api/events";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JwtService jwtService;

    @Test
    void rejectsAnUnauthenticatedRequestToAProtectedPath() throws Exception {
        assertThat(statusOf(PROTECTED_PATH, null)).isEqualTo(401);
    }

    @Test
    void rejectsARefreshTokenPresentedAsABearerCredential() throws Exception {
        User user = User.create("chain@example.com", "$2a$12$hash", Set.of(Role.CUSTOMER), Instant.now());

        assertThat(statusOf(PROTECTED_PATH, jwtService.issueRefreshToken(user))).isEqualTo(401);
    }

    @Test
    void doesNotDemandAuthenticationOnAPermittedPath() throws Exception {
        assertThat(statusOf("/api/events/public", null)).isNotIn(401, 403);
    }

    private int statusOf(String path, String bearerToken) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        }
    }
}
