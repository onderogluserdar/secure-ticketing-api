package com.onderogluserdar.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowTest {

    private static final String EMAIL = "flow@example.com";
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registersThenLogsInThenRefreshes() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials()))
                .andExpect(status().isCreated());

        JsonNode login = postForJson("/api/auth/login", credentials());
        assertThat(login.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(login.get("expiresIn").asLong()).isEqualTo(900L);
        assertThat(login.get("accessToken").asString()).isNotBlank();
        assertThat(login.get("refreshToken").asString()).isNotBlank();

        String refreshBody = """
                {"refreshToken":"%s"}""".formatted(login.get("refreshToken").asString());
        JsonNode refreshed = postForJson("/api/auth/refresh", refreshBody);

        assertThat(refreshed.get("accessToken").asString()).isNotBlank();
        assertThat(refreshed.get("tokenType").asString()).isEqualTo("Bearer");
    }

    @Test
    void refusesToRefreshWithAnAccessToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials()))
                .andExpect(status().isCreated());
        JsonNode login = postForJson("/api/auth/login", credentials());

        String accessTokenAsRefresh = """
                {"refreshToken":"%s"}""".formatted(login.get("accessToken").asString());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accessTokenAsRefresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void refusesToLogInWithAWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials()))
                .andExpect(status().isCreated());

        String wrong = """
                {"email":"%s","password":"not the right password"}""".formatted(EMAIL);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrong))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private static String credentials() {
        return """
                {"email":"%s","password":"%s"}""".formatted(EMAIL, PASSWORD);
    }

    private JsonNode postForJson(String path, String body) throws Exception {
        String response = mockMvc.perform(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
