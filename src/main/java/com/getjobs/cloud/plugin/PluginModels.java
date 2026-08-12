package com.getjobs.cloud.plugin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API models for the plugin binding and device management endpoints.
 * The plaintext token appears only in {@link BindResult.TokenValue} and is
 * returned exactly once at bind time; it is never logged or persisted.
 */
public final class PluginModels {
    private PluginModels() {
    }

    /** Anonymous bind request submitted with a one-time bind code. */
    public record BindRequest(
            String bindCode,
            String installationId,
            String deviceName,
            String browserName,
            String browserVersion,
            String extensionVersion,
            List<String> capabilities
    ) {
    }

    /** Result of a successful bind: device plus the single-use plaintext token. */
    public record BindResult(DeviceView device, TokenValue token) {
    }

    /** Plaintext token value; only present in the bind response. */
    public record TokenValue(String value, Instant expiresAt, List<String> scopes) {
    }

    /** Device fields shared by the bind, device-list and me endpoints. */
    public record DeviceView(
            UUID id,
            String deviceName,
            String browserName,
            String browserVersion,
            String extensionVersion,
            String status,
            List<String> capabilities,
            Instant lastSeenAt,
            Instant boundAt,
            Instant revokedAt,
            String revokeReason
    ) {
    }

    /** Response of {@code GET /api/plugin/me}: minimal user display fields only. */
    public record MeResponse(MinimalUser user, DeviceView device, TokenInfo token) {
    }

    public record MinimalUser(UUID id, String displayName) {
    }

    public record TokenInfo(List<String> scopes, Instant expiresAt) {
    }

    public record BindCodeResult(String bindCode, Instant expiresAt, long expiresInSeconds) {
    }

    public record RevokeDeviceRequest(String reason) {
    }

    public record RevokeDeviceResult(UUID id, String status, Instant revokedAt) {
    }
}
