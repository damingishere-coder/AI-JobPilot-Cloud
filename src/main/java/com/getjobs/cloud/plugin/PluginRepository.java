package com.getjobs.cloud.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for plugin devices and tokens. Token lookup and state changes
 * that run outside the RLS tenant context go through the narrow SECURITY
 * DEFINER functions; device reads/writes from the Web run under RLS.
 */
@Repository
@Profile("api")
public class PluginRepository {

    private static final String DEVICE_COLUMNS = """
            d.id, d.device_name, d.browser_name, d.browser_version, d.extension_version,
            d.status, d.capabilities::text, d.last_seen_at, d.bound_at, d.revoked_at, d.revoke_reason
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PluginRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve a token prefix + hash via the SECURITY DEFINER function. Returns
     * empty for unknown tokens so the caller cannot distinguish invalid from
     * expired. The final constant-time comparison runs inside PostgreSQL and
     * the function never returns the stored hash.
     */
    public Optional<TokenAuthRecord> authenticate(String tokenPrefix, String tokenHash) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT * FROM app.authenticate_plugin_token(CAST(? AS varchar), CAST(? AS char(64)))
                    """,
                    this::mapAuth,
                    tokenPrefix, tokenHash
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** Throttled last_used/last_seen maintenance after successful auth. */
    public void touch(UUID tokenId, UUID deviceId, int intervalSeconds) {
        jdbc.update(
                "SELECT app.touch_plugin_token(CAST(? AS uuid), CAST(? AS uuid), CAST(? AS integer))",
                tokenId, deviceId, intervalSeconds
        );
    }

    /**
     * Atomically create or reuse a device and issue a token hash.
     * Runs without RLS context; the SECURITY DEFINER function validates inputs.
     */
    public BindOutcome bindDevice(
            UUID userId,
            String installationIdHash,
            String deviceName,
            String browserName,
            String browserVersion,
            String extensionVersion,
            String capabilitiesJson,
            String tokenPrefix,
            String tokenHash,
            String scopesJson,
            Instant expiresAt,
            int maxDevices
    ) {
        return jdbc.queryForObject(
                """
                SELECT * FROM app.bind_plugin_device(
                    CAST(? AS uuid), CAST(? AS char(64)), CAST(? AS varchar),
                    CAST(? AS varchar), CAST(? AS varchar), CAST(? AS varchar),
                    CAST(? AS jsonb), CAST(? AS varchar), CAST(? AS char(64)),
                    CAST(? AS jsonb), CAST(? AS timestamptz), CAST(? AS integer)
                )
                """,
                (rs, row) -> new BindOutcome(
                        rs.getString("outcome"),
                        rs.getObject("bound_device_id", UUID.class),
                        rs.getObject("bound_token_id", UUID.class),
                        rs.getBoolean("device_reused")
                ),
                userId, installationIdHash, deviceName, browserName, browserVersion,
                extensionVersion, capabilitiesJson, tokenPrefix, tokenHash, scopesJson,
                java.sql.Timestamp.from(expiresAt), maxDevices
        );
    }

    /** Revoke a device and its tokens; releases live leases. */
    public boolean revokeDevice(UUID userId, UUID deviceId, String reason) {
        Boolean result = jdbc.queryForObject(
                "SELECT app.revoke_plugin_device(CAST(? AS uuid), CAST(? AS uuid), CAST(? AS varchar))",
                Boolean.class,
                userId, deviceId, reason
        );
        return result != null && result;
    }

    public List<DeviceRecord> listDevices(UUID userId) {
        return jdbc.query(
                "SELECT " + DEVICE_COLUMNS + " FROM app.plugin_devices d WHERE d.user_id=? "
                        + "ORDER BY d.created_at DESC, d.id DESC",
                this::mapDevice,
                userId
        );
    }

    public Optional<DeviceRecord> findDevice(UUID userId, UUID deviceId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + DEVICE_COLUMNS + " FROM app.plugin_devices d WHERE d.user_id=? AND d.id=?",
                    this::mapDevice,
                    userId, deviceId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<DeviceRecord> findActiveDevice(UUID userId, UUID deviceId) {
        return findDevice(userId, deviceId)
                .filter(device -> "ACTIVE".equals(device.status()));
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.copyOf((List<String>) objectMapper.readValue(json, List.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private TokenAuthRecord mapAuth(ResultSet rs, int row) throws SQLException {
        return new TokenAuthRecord(
                rs.getObject("token_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getString("token_status"),
                stringList(rs.getString("token_scopes")),
                instant(rs, "token_expires_at"),
                rs.getString("user_status"),
                rs.getString("user_display_name"),
                rs.getString("device_status"),
                stringList(rs.getString("device_capabilities")),
                rs.getString("device_extension_version"),
                rs.getString("device_name")
        );
    }

    private DeviceRecord mapDevice(ResultSet rs, int row) throws SQLException {
        return new DeviceRecord(
                rs.getObject("id", UUID.class),
                rs.getString("device_name"),
                rs.getString("browser_name"),
                rs.getString("browser_version"),
                rs.getString("extension_version"),
                rs.getString("status"),
                stringList(rs.getString("capabilities")),
                instant(rs, "last_seen_at"),
                instant(rs, "bound_at"),
                instant(rs, "revoked_at"),
                rs.getString("revoke_reason")
        );
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    public record TokenAuthRecord(
            UUID tokenId,
            UUID userId,
            UUID deviceId,
            String tokenStatus,
            List<String> scopes,
            Instant tokenExpiresAt,
            String userStatus,
            String userDisplayName,
            String deviceStatus,
            List<String> capabilities,
            String deviceExtensionVersion,
            String deviceName
    ) {
    }

    public record DeviceRecord(
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

    public record BindOutcome(
            String outcome,
            UUID deviceId,
            UUID tokenId,
            boolean deviceReused
    ) {
        public boolean ok() {
            return "OK".equals(outcome);
        }
    }

    /** Sensitive-free device view mapping for API responses. */
    public static PluginModels.DeviceView toView(DeviceRecord device) {
        return new PluginModels.DeviceView(
                device.id(), device.deviceName(), device.browserName(), device.browserVersion(),
                device.extensionVersion(), device.status(), device.capabilities(),
                device.lastSeenAt(), device.boundAt(), device.revokedAt(), device.revokeReason()
        );
    }
}
