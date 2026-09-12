package com.onderogluserdar.ticketing.common.error;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import tools.jackson.databind.ObjectMapper;

public final class ProblemDetails {

    public static final String CODE_PROPERTY = "code";

    private ProblemDetails() {}

    public static ProblemDetail of(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty(CODE_PROPERTY, code.name());
        return problem;
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper, ProblemDetail problem)
            throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
