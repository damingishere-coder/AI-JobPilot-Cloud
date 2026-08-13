package com.getjobs.cloud.auth;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(
        UUID id,
        String email,
        String passwordHash,
        UserRole role,
        UserStatus status,
        int failedLoginCount,
        Instant lockedUntil,
        Instant createdAt
) {
}
