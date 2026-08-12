package com.serviceatlas.api;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders every error as RFC 7807 {@code application/problem+json} (§5).
 *
 * <p>Ordered ahead of Spring Boot's own {@code ProblemDetailsExceptionHandler}, which would
 * otherwise answer validation failures with {@code type: about:blank}. Every error this API
 * produces carries a stable {@code /problems/...} type instead, so clients can branch on it.
 */
@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getMessage());
        problem.setType(URI.create(exception.getType()));
        problem.setTitle(titleFor(exception.getStatus()));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Request validation failed");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create("/problems/invalid-request"));
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setType(URI.create("/problems/invalid-request"));
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        // Spring's own errors — unmapped path, wrong method, unreadable body — already carry a
        // ProblemDetail and their own status. They implement ErrorResponse without sharing a common
        // base class, so the interface is the reliable thing to test for. Passing them through
        // keeps a mistyped URL a 404 instead of flattening it into a 500.
        if (exception instanceof org.springframework.web.ErrorResponse errorResponse) {
            return errorResponse.getBody();
        }
        log.error("Unhandled error", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. See the server log for details.");
        problem.setType(URI.create("/problems/internal-error"));
        problem.setTitle("Internal error");
        return problem;
    }

    private static String titleFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Not found";
            case CONFLICT -> "Conflict";
            case BAD_REQUEST -> "Invalid request";
            default -> status.getReasonPhrase();
        };
    }
}
