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
            case INVALID_CREDENTIALS, INVALID_TOKEN -> HttpStatus.UNAUTHORIZED;
            case EVENT_ACCESS_DENIED, RESERVATION_ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case EVENT_NOT_FOUND, RESERVATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case EMAIL_ALREADY_REGISTERED,
                    EVENT_ALREADY_PUBLISHED,
                    EVENT_NOT_PUBLISHED,
                    CAPACITY_BELOW_RESERVED,
                    INSUFFICIENT_CAPACITY,
                    INVALID_RESERVATION_STATE -> HttpStatus.CONFLICT;
        };
    }
}
