package com.getjobs.cloud.plugin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Trusted identity of an authenticated plugin request, resolved from the
 * opaque Bearer token hash. The user and device ids come from the server-side
 * token record only; request bodies can never override them.
 */
public record PluginPrincipal(
        UUID tokenId,
        UUID userId,
        UUID deviceId,
        String deviceName,
        String userDisplayName,
        List<String> scopes,
        List<String> capabilities,
        String extensionVersion,
        Instant tokenExpiresAt
) {
}
