package com.getjobs.cloud.auth;

import java.util.UUID;

public record UserProfile(
        UUID userId,
        String displayName,
        String city,
        String timezone,
        String locale
) {
}
