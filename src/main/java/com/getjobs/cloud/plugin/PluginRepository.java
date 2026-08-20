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

    /**
     * Throttled last_used/last_seen maintenance after successful auth.
     * The function returns void, so it must be executed as a statement, never
     * through an update-count path.
     */
    public void touch(UUID tokenId, UUID deviceId, int intervalSeconds) {
        jdbc.execute(
                "SELECT app.touch_plugin_token(CAST(? AS uuid), CAST(? AS uuid), CAST(? AS integer))",
                (org.springframework.jdbc.core.PreparedStatementCallback<Void>) preparedStatement -> {
                    preparedStatement.setObject(1, tokenId);
                    preparedStatement.setObject(2, deviceId);
                    preparedStatement.setInt(3, intervalSeconds);
                    preparedStatement.execute();
                    return null;
                }
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

    /**
     * Serialize concurrent bind-code creates for the same user + idempotency
     * key. The advisory lock is held until the caller's transaction ends;
     * pg_advisory_xact_lock returns void, so no column value is read.
     */
    public void lockBindCodeCreate(UUID userId, String idempotencyKeyHash) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtext(CAST(? AS text)))",
                resultSet -> { },
                userId + ":" + idempotencyKeyHash
        );
    }

    /**
     * Create a bind code hash via the SECURITY DEFINER function; runs without
     * RLS context and enforces the account state and the per-user active cap.
     */
    public String createBindCode(UUID userId, String codeHash, Instant expiresAt, int maxActive) {
        return jdbc.queryForObject(
                """
                SELECT outcome FROM app.create_plugin_bind_code(
                    CAST(? AS uuid), CAST(? AS char(64)), CAST(? AS timestamptz), CAST(? AS integer)
                )
                """,
                String.class,
                userId, codeHash, java.sql.Timestamp.from(expiresAt), maxActive
        );
    }

    /**
     * One-shot bind code consumption via the SECURITY DEFINER function; empty
     * for expired/consumed/superseded/unknown codes. Callers run this inside
     * the same transaction as the device/token issuance.
     */
    public Optional<UUID> consumeBindCode(String codeHash) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT app.consume_plugin_bind_code(CAST(? AS char(64)))",
                    UUID.class,
                    codeHash
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Insert or refresh a captured job inside the V4 {@code app.job_posts} pool
     * under the caller's RLS tenant context, so plugin-captured jobs are the
     * same rows the Web {@code /api/jobs} endpoints and the match/delivery flow
     * read. Identity comes from the token principal only; {@code platform} is
     * the canonical {@code BOSS}/{@code ZHILIAN} enum, {@code external_job_id}
     * carries the platform job id and {@code fingerprint} is the server-side
     * SHA-256 of canonical platform + platform job id (never client-supplied).
     *
     * <p>Idempotency rides the V4 unique indexes
     * ({@code (user_id, platform, external_job_id)} and
     * {@code (user_id, platform, fingerprint)}): a duplicate upload of the same
     * job returns the existing row with {@code inserted=false} and only
     * refreshes {@code last_seen_at} (updated_at via the V4 touch trigger).
     * {@code status}, match results, delivery tasks and any user-edited fields
     * are never rewritten, and there is no raw payload column anywhere.
     * The returned inserted flag distinguishes created from duplicate.
     */
    public CaptureOutcome upsertCapturedJob(
            UUID userId,
            String platform,
            String platformJobId,
            String fingerprint,
            String jobUrl,
            String title,
            String salaryText,
            String location,
            String companyName,
            String experience,
            String degree,
            String description,
            String companyInfoJson,
            String skillsJson,
            String welfareJson,
            Instant capturedAt
    ) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.job_posts (
                    user_id, platform, external_job_id, fingerprint, title, company_name,
                    salary_text, location, experience, degree, description, job_url,
                    company_info, skills, welfare, source_captured_at, last_seen_at
                ) VALUES (
                    CAST(? AS uuid), CAST(? AS varchar), CAST(? AS varchar), CAST(? AS char(64)),
                    CAST(? AS varchar), CAST(? AS varchar), CAST(? AS varchar), CAST(? AS varchar),
                    CAST(? AS varchar), CAST(? AS varchar), CAST(? AS text), CAST(? AS text),
                    CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS timestamptz), now()
                )
                ON CONFLICT (user_id, platform, external_job_id) WHERE external_job_id IS NOT NULL
                DO UPDATE SET
                    last_seen_at = now()
                RETURNING id, (xmax = 0) AS inserted
                """,
                (rs, row) -> new CaptureOutcome(
                        rs.getObject("id", UUID.class),
                        rs.getBoolean("inserted")
                ),
                userId, platform, platformJobId, fingerprint, title, companyName,
                salaryText, location, experience, degree, description, jobUrl,
                companyInfoJson, skillsJson, welfareJson,
                java.sql.Timestamp.from(capturedAt)
        );
    }

    public record CaptureOutcome(UUID id, boolean inserted) {
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
