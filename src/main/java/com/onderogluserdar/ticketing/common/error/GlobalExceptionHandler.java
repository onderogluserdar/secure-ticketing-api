package com.onderogluserdar.ticketing.common.error;

import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusinessException(BusinessException exception) {
        return ProblemDetails.of(statusFor(exception.getErrorCode()), exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled failure", exception);
        return ProblemDetails.of(
                HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "the request could not be processed");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem =
                ProblemDetails.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "request validation failed");
        problem.setProperty("errors", fieldErrors(exception));
        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(exception, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem && !hasCode(problem)) {
            problem.setProperty(
                    ProblemDetails.CODE_PROPERTY, codeFor(statusCode).name());
            problem.setDetail(genericDetailFor(statusCode));
        }
        return response;
    }

    private static boolean hasCode(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        return properties != null && properties.containsKey(ProblemDetails.CODE_PROPERTY);
    }

    private static ErrorCode codeFor(HttpStatusCode statusCode) {
        return statusCode.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.INTERNAL_ERROR;
    }

    private static String genericDetailFor(HttpStatusCode statusCode) {
        return statusCode.is4xxClientError() ? "the request could not be read" : "the request could not be processed";
    }

    private static Map<String, String> fieldErrors(MethodArgumentNotValidException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage(),
                        (first, second) -> first));
    }

    private static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS, INVALID_TOKEN, AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case EVENT_ACCESS_DENIED, RESERVATION_ACCESS_DENIED, ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case EVENT_NOT_FOUND, RESERVATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VALIDATION_ERROR, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case RATE_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case EMAIL_ALREADY_REGISTERED,
                    EVENT_ALREADY_PUBLISHED,
                    EVENT_NOT_PUBLISHED,
                    CAPACITY_BELOW_RESERVED,
                    INSUFFICIENT_CAPACITY,
                    INVALID_RESERVATION_STATE,
                    IDEMPOTENCY_KEY_CONFLICT -> HttpStatus.CONFLICT;
        };
    }
}
