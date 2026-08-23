package com.getjobs.cloud.ai;

/**
 * Runtime exception for AI match processing failures.
 * {@code retryable} indicates whether the error may resolve on retry
 * (e.g. network outages, rate limits, transient service errors).
 */
public class AiMatchException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public AiMatchException(String code, String message) {
        this(code, message, false);
    }

    public AiMatchException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
