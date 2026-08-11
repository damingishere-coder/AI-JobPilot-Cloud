package com.getjobs.cloud.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.cloud.web.ApiError;
import com.getjobs.cloud.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@Profile("api")
public class SecurityResponseWriter {
    private final ObjectMapper objectMapper;

    public SecurityResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            boolean retryable
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.failure(new ApiError(code, message, List.of(), retryable))
        );
    }
}
