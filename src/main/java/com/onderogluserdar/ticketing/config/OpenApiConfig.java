package com.onderogluserdar.ticketing.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
class OpenApiConfig {

    static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI ticketingApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Secure Ticketing & Reservation API")
                        .version("1.0.0")
                        .description("""
                                Event ticketing with JWT authentication, role and ownership authorization, \
                                reservations that cannot oversell, and idempotent reservation creation.

                                Obtain a token from POST /api/auth/login and send it as `Authorization: Bearer <token>`."""))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "Access token from /api/auth/login. Refresh tokens are rejected.")))
                // The auth and public discovery operations opt out individually.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    @Bean
    OpenApiCustomizer documentUnauthorizedOnSecuredOperations() {
        return openApi -> openApi.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .filter(operation -> operation.getSecurity() == null)
                .forEach(operation -> operation
                        .getResponses()
                        .computeIfAbsent(
                                "401",
                                status ->
                                        new ApiResponse().description("Missing, expired or wrong-type bearer token")));
    }
}
