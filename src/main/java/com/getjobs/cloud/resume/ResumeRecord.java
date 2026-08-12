package com.getjobs.cloud.resume;

import java.time.Instant;
import java.util.UUID;

public record ResumeRecord(
        UUID id,
        UUID userId,
        String originalFilename,
        String storageKey,
        String contentType,
        long fileSize,
        String sha256,
        String parseStatus,
        String parseMessage,
        byte[] extractedTextCiphertext,
        byte[] extractedTextNonce,
        String encryptionKeyId,
        int textVersion,
        boolean current,
        int version,
        Instant parsedAt,
        Instant deletedAt,
        Instant purgedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
