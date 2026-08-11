package com.getjobs.cloud.web;

import org.springframework.http.HttpStatus;

import java.util.List;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;
    private final long retryAfterSeconds;
    private final List<ApiError.FieldViolation> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, false, 0, List.of());
    }

    public ApiException(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            long retryAfterSeconds,
            List<ApiError.FieldViolation> fieldErrors
    ) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
    public List<ApiError.FieldViolation> fieldErrors() { return fieldErrors; }
}
