package com.getjobs.cloud.resume;

import java.time.Instant;
import java.util.UUID;

public final class ResumeApiModels {
    private ResumeApiModels() {
    }

    public record ResumeView(
            UUID id,
            String originalFilename,
            String contentType,
            long fileSize,
            String parseStatus,
            String parseMessage,
            boolean current,
            int version,
            Instant createdAt,
            Instant updatedAt,
            Instant parsedAt,
            String extractedText
    ) {
    }

    public record UploadPayload(ResumeView resume, boolean deduplicated) {
    }

    public record DeletePayload(UUID id, String deletionStatus, Instant deletedAt, int version) {
    }
}
