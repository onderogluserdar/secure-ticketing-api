package com.onderogluserdar.ticketing.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.onderogluserdar.ticketing.support.LogCapture;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A tiny bucket rather than a real refill wait, so nothing here sleeps. Each test uses its own
 * client address, which both isolates the buckets and demonstrates that the key is the client IP.
 */
@SpringBootTest(properties = {"ticketing.rate-limit.login.capacity=2", "ticketing.rate-limit.login.refill-period=1m"})
@AutoConfigureMockMvc
class LoginRateLimitTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Test
    void countsARejectionWithoutTurningItIntoAnApplicationError() throws Exception {
        String email = register("counted", "10.0.0.9");
        double before = registry.get("ticketing.rate.limit.rejected").counter().count();

        try (LogCapture logs = LogCapture.attach()) {
            mockMvc.perform(login(email, PASSWORD, "10.0.0.9")).andExpect(status().isOk());
            mockMvc.perform(login(email, PASSWORD, "10.0.0.9")).andExpect(status().isOk());
            mockMvc.perform(login(email, PASSWORD, "10.0.0.9")).andExpect(status().isTooManyRequests());

            // Being rate limited is an expected outcome; it must not fill the error log on demand.
            assertThat(logs.errors()).isEmpty();
        }

        assertThat(registry.get("ticketing.rate.limit.rejected").counter().count() - before)
                .isEqualTo(1);
    }

    @Test
    void allowsRequestsWithinTheAllowanceAndRejectsTheNextOne() throws Exception {
        String email = register("within-allowance");

        mockMvc.perform(login(email, PASSWORD, "10.0.0.1")).andExpect(status().isOk());
        mockMvc.perform(login(email, PASSWORD, "10.0.0.1")).andExpect(status().isOk());

        mockMvc.perform(login(email, PASSWORD, "10.0.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void countsRejectedCredentialsAgainstTheAllowanceToo() throws Exception {
        String email = register("wrong-password");

        mockMvc.perform(login(email, "not the password", "10.0.0.2")).andExpect(status().isUnauthorized());
        mockMvc.perform(login(email, "not the password", "10.0.0.2")).andExpect(status().isUnauthorized());

        // The third attempt never reaches authentication, so brute force is what is limited.
        mockMvc.perform(login(email, PASSWORD, "10.0.0.2")).andExpect(status().isTooManyRequests());
    }

    @Test
    void keepsBucketsSeparatePerClientAddress() throws Exception {
        String email = register("per-client");

        mockMvc.perform(login(email, PASSWORD, "10.0.0.3")).andExpect(status().isOk());
        mockMvc.perform(login(email, PASSWORD, "10.0.0.3")).andExpect(status().isOk());
        mockMvc.perform(login(email, PASSWORD, "10.0.0.3")).andExpect(status().isTooManyRequests());

        mockMvc.perform(login(email, PASSWORD, "10.0.0.4")).andExpect(status().isOk());
    }

    @Test
    void revealsNothingAboutTheClientOrTheLimiterState() throws Exception {
        String email = register("no-leak");
        mockMvc.perform(login(email, PASSWORD, "10.0.0.5")).andExpect(status().isOk());
        mockMvc.perform(login(email, PASSWORD, "10.0.0.5")).andExpect(status().isOk());

        String body = mockMvc.perform(login(email, PASSWORD, "10.0.0.5"))
                .andExpect(status().isTooManyRequests())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("10.0.0.5")
                .doesNotContain(email)
                .doesNotContain(PASSWORD)
                .doesNotContain("token")
                .doesNotContain("bucket");
    }

    @Test
    void doesNotLimitOtherEndpoints() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            register("unlimited-" + attempt, "10.0.0.6");
        }
    }

    private String register(String localPart) throws Exception {
        return register(localPart, "10.0.0.99");
    }

    private String register(String localPart, String clientAddress) throws Exception {
        String email = localPart + "-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .with(request -> {
                            request.setRemoteAddr(clientAddress);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        return email;
    }

    private static MockHttpServletRequestBuilder login(String email, String password, String clientAddress) {
        return post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(clientAddress);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }
}
