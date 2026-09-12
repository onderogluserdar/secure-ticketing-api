package com.onderogluserdar.ticketing.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.common.error.ProblemDetails;

import tools.jackson.databind.ObjectMapper;

@Configuration
class ProblemDetailSecurityHandlers {

    @Bean
    AuthenticationEntryPoint problemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) -> {
            bearer.commence(request, response, exception);
            writeProblem(
                    response,
                    objectMapper,
                    HttpStatus.UNAUTHORIZED,
                    ErrorCode.AUTHENTICATION_REQUIRED,
                    "authentication is required");
        };
    }

    @Bean
    AccessDeniedHandler problemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) -> {
            bearer.handle(request, response, exception);
            writeProblem(
                    response,
                    objectMapper,
                    HttpStatus.FORBIDDEN,
                    ErrorCode.ACCESS_DENIED,
                    "this role may not use this endpoint");
        };
    }

    private static void writeProblem(
            HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status, ErrorCode code, String detail)
            throws IOException {
        if (!response.isCommitted()) {
            ProblemDetails.write(response, objectMapper, ProblemDetails.of(status, code, detail));
        }
    }
}
