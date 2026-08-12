package com.serviceatlas.api;

import org.springframework.http.HttpStatus;

/**
 * An error with a status and a stable machine-readable type, rendered as RFC 7807 problem+json by
 * {@link ApiExceptionHandler} (§5).
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String type;

    public ApiException(HttpStatus status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    public ApiException(HttpStatus status, String type, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.type = type;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** URI reference identifying the problem type, e.g. {@code /problems/workspace-not-found}. */
    public String getType() {
        return type;
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "/problems/not-found",
                what + " " + id + " does not exist");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "/problems/invalid-request", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "/problems/conflict", message);
    }

    public static ApiException notConfigured(String message) {
        return new ApiException(HttpStatus.CONFLICT, "/problems/not-configured", message);
    }
}
