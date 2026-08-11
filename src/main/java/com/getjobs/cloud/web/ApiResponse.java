package com.getjobs.cloud.web;

import org.slf4j.MDC;

public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        String requestId
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, currentRequestId());
    }

    public static ApiResponse<Void> failure(ApiError error) {
        return new ApiResponse<>(false, null, error, currentRequestId());
    }

    private static String currentRequestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null ? "unknown" : requestId;
    }
}
