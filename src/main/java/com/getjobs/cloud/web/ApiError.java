package com.getjobs.cloud.web;

import java.util.List;

public record ApiError(
        String code,
        String message,
        List<FieldViolation> fieldErrors,
        boolean retryable
) {
    public record FieldViolation(String field, String reason) {
    }
}
