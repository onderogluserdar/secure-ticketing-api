package com.onderogluserdar.ticketing.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusinessException(BusinessException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(statusFor(exception.getErrorCode()), exception.getMessage());
        problem.setProperty("code", exception.getErrorCode().name());
        return problem;
    }

    private static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            case EMAIL_ALREADY_REGISTERED,
                    EVENT_ALREADY_PUBLISHED,
                    CAPACITY_BELOW_RESERVED,
                    INVALID_RESERVATION_STATE -> HttpStatus.CONFLICT;
        };
    }
}
