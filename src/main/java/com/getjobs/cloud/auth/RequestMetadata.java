package com.getjobs.cloud.auth;

import jakarta.servlet.http.HttpServletRequest;

public record RequestMetadata(String remoteAddress, String userAgent) {
    public static RequestMetadata from(HttpServletRequest request) {
        return new RequestMetadata(
                request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr(),
                request.getHeader("User-Agent")
        );
    }
}
