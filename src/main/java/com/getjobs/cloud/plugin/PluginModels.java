package com.getjobs.cloud.plugin;

import com.fasterxml.jackson.annotation.JsonAlias;

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

    /** Heartbeat response: the trusted device/user ids plus the current state. */
    public record HeartbeatResponse(UUID deviceId, UUID userId, String status, Instant lastSeenAt) {
    }

    /**
     * Job capture upload. The user and device ids never come from this request:
     * the service resolves them from the authenticated {@link PluginPrincipal}
     * only, and unknown JSON fields are rejected by the strict deserializer.
     * Multi-word fields accept the documented snake_case spellings as precise
     * aliases ({@code platform_job_id}, {@code job_url}, ...); there are no
     * aliases for {@code user_id}/{@code device_id}, so a forged identity field
     * is always a strict 400.
     */
    public record CaptureJobRequest(
            String platform,
            @JsonAlias("platform_job_id") String platformJobId,
            @JsonAlias("job_url") String jobUrl,
            String title,
            String salary,
            String city,
            String district,
            @JsonAlias("company_name") String companyName,
            @JsonAlias("company_size") String companySize,
            String industry,
            String experience,
            String education,
            List<String> benefits,
            @JsonAlias("job_description") String jobDescription,
            @JsonAlias("hr_name") String hrName,
            @JsonAlias("captured_at") String capturedAt
    ) {
    }

    public record CaptureBatchRequest(List<CaptureJobRequest> items) {
    }

    /** Single capture result: the job_posts id plus created/duplicate. */
    public record CaptureResult(UUID id, String status) {
    }

    /** Per-item batch result; failed items carry a bounded error code/message. */
    public record CaptureBatchItem(UUID id, String status, String errorCode, String message) {
    }

    /**
     * Batch result with items plus the per-status counts. Every count is
     * strictly consistent with {@code items}: total equals the request size and
     * created + duplicates + failed equals total.
     */
    public record CaptureBatchResult(
            List<CaptureBatchItem> items,
            int created,
            int duplicates,
            int failed,
            int total
    ) {
    }
}
