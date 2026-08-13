package com.getjobs.cloud.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for delivery tasks and events. Web reads/writes run under the
 * RLS tenant context; plugin state transitions go through the narrow
 * SECURITY DEFINER functions so a leaked plugin token cannot write state
 * directly.
 */
@Repository
@Profile("api")
public class DeliveryRepository {
    private static final String TASK_COLUMNS = """
            t.id, t.job_post_id, t.job_match_id, t.assigned_device_id, t.status, t.greeting,
            t.confirmation_version, t.confirmed_at, t.idempotency_payload_hash,
            t.lease_id, t.leased_at, t.lease_expires_at, t.execution_id, t.attempt_count,
            t.last_error_code, t.last_error_message, t.last_error_retryable,
            t.started_at, t.finished_at, t.version, t.created_at, t.updated_at
            """;

    /**
     * Fixed list/count SQL. Every filter is always present and guarded by a
     * boolean bind parameter, so a request value can never change the SQL
     * text; only JDBC bind values vary at runtime. Disabled filters bind
     * explicit placeholder values because PostgreSQL cannot infer the type of
     * a NULL bind; the status multi-select always binds a non-empty
     * collection so an empty selection can never produce an invalid IN ().
     * The TASK_COUNT_SQL WHERE block must stay identical to the TASK_LIST_SQL
     * WHERE block.
     */
    private static final String TASK_COUNT_SQL = """
            SELECT count(*) FROM app.delivery_tasks t
            JOIN app.job_posts jp ON jp.id=t.job_post_id AND jp.user_id=t.user_id
            WHERE t.user_id=:userId
              AND (:filterStatuses=false OR t.status IN (:statuses))
              AND (:filterPlatform=false OR jp.platform=:platform)
              AND (:filterKeyword=false OR (jp.title ILIKE :keyword OR jp.company_name ILIKE :keyword))
              AND (:filterCreatedFrom=false OR t.created_at>=:createdFrom)
              AND (:filterCreatedTo=false OR t.created_at<=:createdTo)
            """;

    /**
     * Sorting is resolved through the fixed CASE branches below: exactly one
     * branch is non-NULL for the bound :sort key, every other branch is NULL
     * (NULLS LAST), and t.id ASC is the stable tie-breaker.
     */
    private static final String TASK_LIST_SQL = """
            SELECT t.id, t.status, t.greeting, t.version, t.confirmation_version, t.confirmed_at,
            t.created_at, t.updated_at,
            jp.id AS job_id, jp.platform, jp.title, jp.company_name, jp.job_url,
            m.id AS match_id, m.score, m.decision,
            d.id AS device_id, d.device_name,
            e.event_type AS last_event_type, e.created_at AS last_event_at,
            e.id AS last_event_id, e.from_status AS last_from, e.to_status AS last_to,
            e.actor_type AS last_actor, e.details::text AS last_details
            FROM app.delivery_tasks t
            JOIN app.job_posts jp ON jp.id=t.job_post_id AND jp.user_id=t.user_id
            LEFT JOIN app.job_matches m ON m.id=t.job_match_id AND m.user_id=t.user_id
            LEFT JOIN app.plugin_devices d ON d.id=t.assigned_device_id AND d.user_id=t.user_id
            LEFT JOIN LATERAL (
              SELECT id, event_type, from_status, to_status, actor_type, created_at, details
              FROM app.delivery_task_events le WHERE le.delivery_task_id=t.id AND le.user_id=t.user_id
              ORDER BY le.id DESC LIMIT 1) e ON true
            WHERE t.user_id=:userId
              AND (:filterStatuses=false OR t.status IN (:statuses))
              AND (:filterPlatform=false OR jp.platform=:platform)
              AND (:filterKeyword=false OR (jp.title ILIKE :keyword OR jp.company_name ILIKE :keyword))
              AND (:filterCreatedFrom=false OR t.created_at>=:createdFrom)
              AND (:filterCreatedTo=false OR t.created_at<=:createdTo)
            ORDER BY
              CASE WHEN :sort='CREATED_ASC' THEN t.created_at END ASC NULLS LAST,
              CASE WHEN :sort='CREATED_DESC' THEN t.created_at END DESC NULLS LAST,
              CASE WHEN :sort='UPDATED_ASC' THEN t.updated_at END ASC NULLS LAST,
              CASE WHEN :sort='UPDATED_DESC' THEN t.updated_at END DESC NULLS LAST,
              CASE WHEN :sort='CONFIRMED_ASC' THEN t.confirmed_at END ASC NULLS LAST,
              CASE WHEN :sort='CONFIRMED_DESC' THEN t.confirmed_at END DESC NULLS LAST,
              t.id ASC
            LIMIT :limit OFFSET :offset
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper;

    public DeliveryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = objectMapper;
    }

    // ---- creation and idempotency ----

    /** Inserts a task; returns the id, or empty when an idempotency/active-task conflict occurred. */
    public Optional<UUID> insertTask(
            UUID id, UUID userId, UUID jobPostId, UUID jobMatchId, String status,
            String greeting, String keyHash, String payloadHash
    ) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    INSERT INTO app.delivery_tasks (
                        id, user_id, job_post_id, job_match_id, status, greeting,
                        idempotency_key_hash, idempotency_payload_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS char(64)), CAST(? AS char(64)))
                    ON CONFLICT DO NOTHING
                    RETURNING id
                    """,
                    (rs, row) -> rs.getObject("id", UUID.class),
                    id, userId, jobPostId, jobMatchId, status, greeting, keyHash, payloadHash
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<TaskRecord> findById(UUID userId, UUID taskId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + TASK_COLUMNS + " FROM app.delivery_tasks t WHERE t.user_id=? AND t.id=?",
                    this::mapTask,
                    userId, taskId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Task read under a row lock (SELECT ... FOR UPDATE). Used by the Web
     * confirm/skip flows so concurrent identical requests serialize on the
     * task row: the second request re-reads the committed state, sees the
     * existing event and replays instead of double-writing state, events or
     * audit rows.
     */
    public Optional<TaskRecord> findByIdForUpdate(UUID userId, UUID taskId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + TASK_COLUMNS + " FROM app.delivery_tasks t "
                            + "WHERE t.user_id=? AND t.id=? FOR UPDATE",
                    this::mapTask,
                    userId, taskId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<TaskRecord> findByKeyHash(UUID userId, String keyHash) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + TASK_COLUMNS + " FROM app.delivery_tasks t "
                            + "WHERE t.user_id=? AND t.idempotency_key_hash=CAST(? AS char(64))",
                    this::mapTask,
                    userId, keyHash
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<TaskRecord> findActiveForJob(UUID userId, UUID jobPostId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + TASK_COLUMNS + " FROM app.delivery_tasks t WHERE t.user_id=? AND t.job_post_id=?"
                            + " AND t.status IN ('PENDING_CONFIRMATION','CONFIRMED','LEASED','EXECUTING','PAUSED')",
                    this::mapTask,
                    userId, jobPostId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    // ---- Web state transitions (RLS-gated, optimistic version) ----

    /**
     * Edits the greeting and, when the edited content invalidates a previous
     * confirmation (CONFIRMED/PAUSED or retryable FAILED), rolls the task back
     * to PENDING_CONFIRMATION and clears every execution/confirmation field so
     * the user must confirm again. Non-retryable FAILED is excluded in the
     * WHERE clause so terminal business limits cannot be bypassed by an edit.
     * Zero rows (version/status changed concurrently) return false, never 500.
     */
    public boolean updateGreeting(UUID userId, UUID taskId, int expectedVersion, String greeting) {
        try {
            Integer updated = jdbc.queryForObject(
                    """
                    UPDATE app.delivery_tasks
                    SET greeting = ?,
                        status = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                           OR (status = 'FAILED' AND last_error_retryable)
                                      THEN 'PENDING_CONFIRMATION' ELSE status END,
                        confirmed_at = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                                OR (status = 'FAILED' AND last_error_retryable)
                                           THEN NULL ELSE confirmed_at END,
                        confirmed_by = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                                OR (status = 'FAILED' AND last_error_retryable)
                                           THEN NULL ELSE confirmed_by END,
                        assigned_device_id = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                                       OR (status = 'FAILED' AND last_error_retryable)
                                                  THEN NULL ELSE assigned_device_id END,
                        lease_id = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                             OR (status = 'FAILED' AND last_error_retryable)
                                        THEN NULL ELSE lease_id END,
                        leased_at = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                             OR (status = 'FAILED' AND last_error_retryable)
                                        THEN NULL ELSE leased_at END,
                        lease_expires_at = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                                     OR (status = 'FAILED' AND last_error_retryable)
                                                THEN NULL ELSE lease_expires_at END,
                        execution_id = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                                OR (status = 'FAILED' AND last_error_retryable)
                                           THEN NULL ELSE execution_id END,
                        last_error_code = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                                    OR (status = 'FAILED' AND last_error_retryable)
                                               THEN NULL ELSE last_error_code END,
                        last_error_message = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                                       OR (status = 'FAILED' AND last_error_retryable)
                                                  THEN NULL ELSE last_error_message END,
                        last_error_retryable = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                                         OR (status = 'FAILED' AND last_error_retryable)
                                                    THEN NULL ELSE last_error_retryable END,
                        finished_at = CASE WHEN status IN ('CONFIRMED', 'PAUSED')
                                               OR (status = 'FAILED' AND last_error_retryable)
                                          THEN NULL ELSE finished_at END,
                        version = version + 1
                    WHERE user_id = ? AND id = ? AND version = ?
                      AND (status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'PAUSED')
                           OR (status = 'FAILED' AND last_error_retryable))
                    RETURNING version
                    """,
                    (rs, row) -> rs.getInt(1),
                    greeting, userId, taskId, expectedVersion
            );
            return updated != null;
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        }
    }

    public boolean confirmTask(UUID userId, UUID taskId, int expectedVersion, UUID assignedDeviceId) {
        try {
            Integer updated = jdbc.queryForObject(
                    """
                    UPDATE app.delivery_tasks
                    SET status = 'CONFIRMED',
                        confirmed_at = now(),
                        confirmed_by = ?,
                        confirmation_version = confirmation_version + 1,
                        assigned_device_id = ?,
                        lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
                        execution_id = NULL,
                        last_error_code = NULL, last_error_message = NULL, last_error_retryable = NULL,
                        finished_at = NULL,
                        version = version + 1
                    WHERE user_id = ? AND id = ? AND version = ?
                      AND (status IN ('PENDING_CONFIRMATION', 'PAUSED')
                           OR (status = 'FAILED' AND last_error_retryable))
                    RETURNING version
                    """,
                    (rs, row) -> rs.getInt(1),
                    userId, assignedDeviceId, userId, taskId, expectedVersion
            );
            return updated != null;
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        }
    }

    public boolean skipTask(UUID userId, UUID taskId, int expectedVersion) {
        try {
            Integer updated = jdbc.queryForObject(
                    """
                    UPDATE app.delivery_tasks
                    SET status = 'SKIPPED',
                        finished_at = now(),
                        confirmed_at = NULL,
                        confirmed_by = NULL,
                        assigned_device_id = NULL,
                        lease_id = NULL, leased_at = NULL, lease_expires_at = NULL,
                        execution_id = NULL,
                        version = version + 1
                    WHERE user_id = ? AND id = ? AND version = ?
                      AND status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'PAUSED', 'FAILED')
                    RETURNING version
                    """,
                    (rs, row) -> rs.getInt(1),
                    userId, taskId, expectedVersion
            );
            return updated != null;
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        }
    }

    // ---- events ----

    public void insertEvent(
            UUID userId, UUID taskId, String eventType, String fromStatus, String toStatus,
            String actorType, UUID actorId, String requestId, String eventKey,
            String idempotencyKeyHash, Map<String, ?> details
    ) {
        jdbc.update(
                """
                INSERT INTO app.delivery_task_events (
                    user_id, delivery_task_id, event_type, from_status, to_status,
                    actor_type, actor_id, request_id, event_key, idempotency_key_hash, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS char(64)), CAST(? AS jsonb))
                """,
                userId, taskId, eventType, fromStatus, toStatus,
                actorType, actorId, requestId, eventKey, idempotencyKeyHash, toJson(details)
        );
    }

    public Optional<EventRow> findEvent(UUID userId, UUID taskId, String eventKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, event_type, from_status, to_status, actor_type, actor_id, created_at, details::text
                    FROM app.delivery_task_events
                    WHERE user_id=? AND delivery_task_id=? AND event_key=?
                    """,
                    this::mapEvent,
                    userId, taskId, eventKey
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Event lookup by the per-task unique idempotency hash. Detects a key that
     * was already used for a different event (e.g. confirm vs skip) so the
     * service can answer a stable 409 instead of tripping the unique index.
     */
    public Optional<EventRow> findEventByKeyHash(UUID userId, UUID taskId, String keyHash) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT id, event_type, from_status, to_status, actor_type, actor_id, created_at, details::text
                    FROM app.delivery_task_events
                    WHERE user_id=? AND delivery_task_id=? AND idempotency_key_hash=CAST(? AS char(64))
                    """,
                    this::mapEvent,
                    userId, taskId, keyHash
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<EventRow> listEvents(UUID userId, UUID taskId) {
        return jdbc.query(
                """
                SELECT id, event_type, from_status, to_status, actor_type, actor_id, created_at, details::text
                FROM app.delivery_task_events
                WHERE user_id=? AND delivery_task_id=?
                ORDER BY id ASC
                """,
                this::mapEvent,
                userId, taskId
        );
    }

    // ---- Web list / detail ----

    public long count(UUID userId, TaskQuery query) {
        Long total = named.queryForObject(TASK_COUNT_SQL, query.parameters(userId), Long.class);
        return total == null ? 0 : total;
    }

    public List<TaskListRow> list(UUID userId, TaskQuery query) {
        MapSqlParameterSource parameters = query.parameters(userId)
                .addValue("limit", query.size())
                .addValue("offset", (query.page() - 1) * query.size());
        return named.query(TASK_LIST_SQL, parameters, this::mapListRow);
    }

    /**
     * Resolves the finite sort enum to the matching hard-coded CASE branch key
     * inside the fixed TASK_LIST_SQL. The resolved key only ever travels as a
     * bind parameter; no client text can reach the SQL string.
     */
    private static String sortKey(TaskSort sort) {
        if (sort == null) {
            return "CREATED_DESC";
        }
        return switch (sort) {
            case CREATED_ASC -> "CREATED_ASC";
            case CREATED_DESC -> "CREATED_DESC";
            case UPDATED_ASC -> "UPDATED_ASC";
            case UPDATED_DESC -> "UPDATED_DESC";
            case CONFIRMED_ASC -> "CONFIRMED_ASC";
            case CONFIRMED_DESC -> "CONFIRMED_DESC";
        };
    }

    // ---- plugin pending list ----

    public List<PendingTaskRow> findPendingForDevice(
            UUID userId, UUID deviceId, List<String> capabilities, String platform, int limit
    ) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.version, jp.platform, jp.job_url, jp.external_job_id, "
                        + "jp.title, jp.company_name, t.greeting, t.confirmed_at, t.confirmation_version "
                        + "FROM app.delivery_tasks t "
                        + "JOIN app.job_posts jp ON jp.id=t.job_post_id AND jp.user_id=t.user_id "
                        + "WHERE t.user_id=? AND t.status='CONFIRMED' "
                        + "AND (t.assigned_device_id IS NULL OR t.assigned_device_id=?) "
                        + "AND jp.platform IN (?, ?)"
        );
        List<Object> parameters = new ArrayList<>(List.of(userId, deviceId,
                capabilities.contains("BOSS") ? "BOSS" : "__none__",
                capabilities.contains("ZHILIAN") ? "ZHILIAN" : "__none__"));
        if (platform != null) {
            sql.append(" AND jp.platform=?");
            parameters.add(platform);
        }
        sql.append(" ORDER BY t.confirmed_at ASC, t.created_at ASC LIMIT ?");
        parameters.add(limit);
        return jdbc.query(sql.toString(), this::mapPendingRow, parameters.toArray());
    }

    // ---- plugin state transition functions ----

    public StartOutcome pluginStart(
            UUID userId, UUID deviceId, UUID taskId, int expectedVersion,
            String executionId, String idempotencyKeyHash, String payloadHash,
            int leaseSeconds, int maxAttempts
    ) {
        return jdbc.queryForObject(
                """
                SELECT * FROM app.plugin_task_start(
                    CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS integer),
                    CAST(? AS varchar), CAST(? AS char(64)), CAST(? AS varchar),
                    CAST(? AS integer), CAST(? AS integer)
                )
                """,
                this::mapStart,
                userId, deviceId, taskId, expectedVersion, executionId,
                idempotencyKeyHash, payloadHash, leaseSeconds, maxAttempts
        );
    }

    public FinishOutcome pluginSuccess(
            UUID userId, UUID deviceId, UUID taskId, UUID leaseId, String executionId,
            int expectedVersion, Instant completedAt, String resultCode, Map<String, ?> evidence,
            String idempotencyKeyHash, String payloadHash
    ) {
        return jdbc.queryForObject(
                """
                SELECT * FROM app.plugin_task_success(
                    CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                    CAST(? AS varchar), CAST(? AS integer), CAST(? AS timestamptz),
                    CAST(? AS varchar), CAST(? AS jsonb), CAST(? AS char(64)), CAST(? AS varchar)
                )
                """,
                this::mapSuccess,
                userId, deviceId, taskId, leaseId, executionId, expectedVersion,
                completedAt == null ? null : java.sql.Timestamp.from(completedAt),
                resultCode, toJson(evidence), idempotencyKeyHash, payloadHash
        );
    }

    public FinishOutcome pluginFail(
            UUID userId, UUID deviceId, UUID taskId, UUID leaseId, String executionId,
            int expectedVersion, Instant failedAt, String errorCode, String message, boolean retryable,
            String idempotencyKeyHash, String payloadHash
    ) {
        return jdbc.queryForObject(
                """
                SELECT * FROM app.plugin_task_fail(
                    CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                    CAST(? AS varchar), CAST(? AS integer), CAST(? AS timestamptz),
                    CAST(? AS varchar), CAST(? AS varchar), CAST(? AS boolean),
                    CAST(? AS char(64)), CAST(? AS varchar)
                )
                """,
                this::mapFinish,
                userId, deviceId, taskId, leaseId, executionId, expectedVersion,
                failedAt == null ? null : java.sql.Timestamp.from(failedAt),
                errorCode, message, retryable, idempotencyKeyHash, payloadHash
        );
    }

    public PauseOutcome pluginPause(
            UUID userId, UUID deviceId, UUID taskId, UUID leaseId, String executionId,
            int expectedVersion, String reason, String message,
            String idempotencyKeyHash, String payloadHash
    ) {
        return jdbc.queryForObject(
                """
                SELECT * FROM app.plugin_task_pause(
                    CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                    CAST(? AS varchar), CAST(? AS integer), CAST(? AS varchar), CAST(? AS varchar),
                    CAST(? AS char(64)), CAST(? AS varchar)
                )
                """,
                (rs, row) -> new PauseOutcome(rs.getString("outcome"), getInt(rs, "new_version")),
                userId, deviceId, taskId, leaseId, executionId, expectedVersion, reason, message,
                idempotencyKeyHash, payloadHash
        );
    }

    /** Lease expiry sweep; returns the number of recovered rows. */
    public int recoverExpiredLeases(int maxAttempts) {
        List<String> statuses = jdbc.query(
                "SELECT recovered_status FROM app.recover_expired_delivery_leases(CAST(? AS integer))",
                (rs, row) -> rs.getString(1),
                maxAttempts
        );
        return statuses.size();
    }

    // ---- job pool integration ----

    public List<TaskStatusRow> findLatestTasksByJobIds(UUID userId, List<UUID> jobPostIds) {
        if (jobPostIds.isEmpty()) {
            return List.of();
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        StringBuilder in = new StringBuilder();
        for (UUID jobId : jobPostIds) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append("?");
            parameters.add(jobId);
        }
        return jdbc.query(
                "SELECT DISTINCT ON (t.job_post_id) t.id, t.job_post_id, t.status, t.created_at, t.confirmed_at "
                        + "FROM app.delivery_tasks t WHERE t.user_id=? AND t.job_post_id IN (" + in + ") "
                        + "ORDER BY t.job_post_id, t.created_at DESC, t.id DESC",
                this::mapTaskStatus,
                parameters.toArray()
        );
    }

    public Optional<TaskDetailRow> findLatestTaskByJob(UUID userId, UUID jobPostId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT t.id, t.status, t.greeting, t.version, t.confirmation_version, "
                            + "t.confirmed_at, t.created_at, t.finished_at "
                            + "FROM app.delivery_tasks t WHERE t.user_id=? AND t.job_post_id=? "
                            + "ORDER BY t.created_at DESC, t.id DESC LIMIT 1",
                    this::mapTaskDetail,
                    userId, jobPostId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    // ---- mapping ----

    private TaskRecord mapTask(ResultSet rs, int row) throws SQLException {
        return new TaskRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("job_post_id", UUID.class),
                rs.getObject("job_match_id", UUID.class),
                rs.getObject("assigned_device_id", UUID.class),
                rs.getString("status"),
                rs.getString("greeting"),
                rs.getInt("confirmation_version"),
                instant(rs, "confirmed_at"),
                rs.getString("idempotency_payload_hash"),
                rs.getObject("lease_id", UUID.class),
                instant(rs, "leased_at"),
                instant(rs, "lease_expires_at"),
                rs.getString("execution_id"),
                rs.getInt("attempt_count"),
                rs.getString("last_error_code"),
                rs.getString("last_error_message"),
                rs.getBoolean("last_error_retryable"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private TaskListRow mapListRow(ResultSet rs, int row) throws SQLException {
        return new TaskListRow(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("greeting"),
                rs.getInt("version"),
                rs.getInt("confirmation_version"),
                instant(rs, "confirmed_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                new DeliveryModels.JobRef(
                        rs.getObject("job_id", UUID.class),
                        rs.getString("platform"),
                        rs.getString("title"),
                        rs.getString("company_name"),
                        rs.getString("job_url")
                ),
                matchRef(rs),
                deviceRef(rs),
                lastEvent(rs)
        );
    }

    private DeliveryModels.MatchRef matchRef(ResultSet rs) throws SQLException {
        UUID matchId = rs.getObject("match_id", UUID.class);
        if (matchId == null) {
            return null;
        }
        return new DeliveryModels.MatchRef(matchId, getInt(rs, "score"), rs.getString("decision"));
    }

    private DeliveryModels.DeviceRef deviceRef(ResultSet rs) throws SQLException {
        UUID deviceId = rs.getObject("device_id", UUID.class);
        if (deviceId == null) {
            return null;
        }
        return new DeliveryModels.DeviceRef(deviceId, rs.getString("device_name"));
    }

    private DeliveryModels.EventView lastEvent(ResultSet rs) throws SQLException {
        String eventType = rs.getString("last_event_type");
        if (eventType == null) {
            return null;
        }
        return new DeliveryModels.EventView(
                rs.getLong("last_event_id"),
                eventType,
                rs.getString("last_from"),
                rs.getString("last_to"),
                rs.getString("last_actor"),
                instant(rs, "last_event_at"),
                map(rs.getString("last_details"))
        );
    }

    private EventRow mapEvent(ResultSet rs, int row) throws SQLException {
        return new EventRow(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("actor_type"),
                rs.getObject("actor_id", UUID.class),
                instant(rs, "created_at"),
                map(rs.getString("details"))
        );
    }

    private PendingTaskRow mapPendingRow(ResultSet rs, int row) throws SQLException {
        return new PendingTaskRow(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getString("platform"),
                rs.getString("job_url"),
                rs.getString("external_job_id"),
                rs.getString("title"),
                rs.getString("company_name"),
                rs.getString("greeting"),
                instant(rs, "confirmed_at"),
                rs.getInt("confirmation_version")
        );
    }

    private StartOutcome mapStart(ResultSet rs, int row) throws SQLException {
        return new StartOutcome(
                rs.getString("outcome"),
                rs.getObject("new_lease_id", UUID.class),
                instant(rs, "new_lease_expires_at"),
                getInt(rs, "attempt_number"),
                getInt(rs, "new_version"),
                rs.getString("task_status"),
                rs.getString("job_platform"),
                rs.getString("job_url"),
                rs.getString("job_title"),
                rs.getString("job_company"),
                rs.getString("task_greeting")
        );
    }

    private FinishOutcome mapFinish(ResultSet rs, int row) throws SQLException {
        return new FinishOutcome(
                rs.getString("outcome"),
                getInt(rs, "new_version"),
                instant(rs, "finished_at"),
                getInt(rs, "attempt_number")
        );
    }

    /** plugin_task_success returns three columns; attempt numbers do not exist there. */
    private FinishOutcome mapSuccess(ResultSet rs, int row) throws SQLException {
        return new FinishOutcome(
                rs.getString("outcome"),
                getInt(rs, "new_version"),
                instant(rs, "finished_at"),
                null
        );
    }

    private TaskStatusRow mapTaskStatus(ResultSet rs, int row) throws SQLException {
        return new TaskStatusRow(
                rs.getObject("id", UUID.class),
                rs.getObject("job_post_id", UUID.class),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs, "confirmed_at")
        );
    }

    private TaskDetailRow mapTaskDetail(ResultSet rs, int row) throws SQLException {
        return new TaskDetailRow(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("greeting"),
                rs.getInt("version"),
                rs.getInt("confirmation_version"),
                instant(rs, "confirmed_at"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs, "finished_at")
        );
    }

    // ---- helpers ----

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化任务事件详情", exception);
        }
    }

    private Map<String, Object> map(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private Integer getInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    // ---- records ----

    public record TaskRecord(
            UUID id,
            UUID jobPostId,
            UUID jobMatchId,
            UUID assignedDeviceId,
            String status,
            String greeting,
            int confirmationVersion,
            Instant confirmedAt,
            String idempotencyPayloadHash,
            UUID leaseId,
            Instant leasedAt,
            Instant leaseExpiresAt,
            String executionId,
            int attemptCount,
            String lastErrorCode,
            String lastErrorMessage,
            boolean lastErrorRetryable,
            Instant startedAt,
            Instant finishedAt,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record TaskListRow(
            UUID id,
            String status,
            String greeting,
            int version,
            int confirmationVersion,
            Instant confirmedAt,
            Instant createdAt,
            Instant updatedAt,
            DeliveryModels.JobRef job,
            DeliveryModels.MatchRef match,
            DeliveryModels.DeviceRef device,
            DeliveryModels.EventView lastEvent
    ) {
    }

    public record EventRow(
            long id,
            String eventType,
            String fromStatus,
            String toStatus,
            String actorType,
            UUID actorId,
            Instant createdAt,
            Map<String, Object> details
    ) {
    }

    public record PendingTaskRow(
            UUID id,
            int version,
            String platform,
            String jobUrl,
            String externalJobId,
            String title,
            String companyName,
            String greeting,
            Instant confirmedAt,
            int confirmationVersion
    ) {
    }

    public record StartOutcome(
            String outcome,
            UUID leaseId,
            Instant leaseExpiresAt,
            Integer attemptNumber,
            Integer newVersion,
            String taskStatus,
            String platform,
            String jobUrl,
            String title,
            String company,
            String greeting
    ) {
        public boolean ok() {
            return "OK".equals(outcome);
        }

        public boolean replay() {
            return "REPLAY".equals(outcome);
        }
    }

    public record FinishOutcome(String outcome, Integer newVersion, Instant finishedAt, Integer attemptCount) {
        public boolean ok() {
            return "OK".equals(outcome);
        }

        public boolean replay() {
            return "REPLAY".equals(outcome);
        }
    }

    public record PauseOutcome(String outcome, Integer newVersion) {
        public boolean ok() {
            return "OK".equals(outcome);
        }

        public boolean replay() {
            return "REPLAY".equals(outcome);
        }
    }

    public record TaskStatusRow(UUID id, UUID jobPostId, String status, Instant createdAt, Instant confirmedAt) {
    }

    public record TaskDetailRow(
            UUID id,
            String status,
            String greeting,
            int version,
            int confirmationVersion,
            Instant confirmedAt,
            Instant createdAt,
            Instant finishedAt
    ) {
    }

    /**
     * Finite sort key for the task list. The repository resolves each value to
     * a hard-coded CASE branch key inside the fixed TASK_LIST_SQL; no
     * free-form SQL string is ever passed through this type.
     */
    public enum TaskSort {
        CREATED_ASC, CREATED_DESC,
        UPDATED_ASC, UPDATED_DESC,
        CONFIRMED_ASC, CONFIRMED_DESC
    }

    /** Validated list filter carried from the service layer. */
    public record TaskQuery(
            int page,
            int size,
            List<String> statuses,
            String platform,
            String keyword,
            Instant createdFrom,
            Instant createdTo,
            TaskSort sort
    ) {
        /** Placeholder collection bound when no status is selected; the filter is disabled via the boolean flag. */
        private static final List<String> NO_STATUSES = List.of("__NONE__");

        /**
         * Binds every filter exactly once as an enable flag plus an explicit
         * value. Disabled filters carry fixed placeholder values (the status
         * collection is always non-empty), so PostgreSQL always sees a typed
         * parameter and an empty selection can never produce an invalid IN ().
         */
        MapSqlParameterSource parameters(UUID userId) {
            MapSqlParameterSource parameters = new MapSqlParameterSource("userId", userId);
            parameters.addValue("filterStatuses", !statuses.isEmpty());
            parameters.addValue("statuses", statuses.isEmpty() ? NO_STATUSES : statuses);
            parameters.addValue("filterPlatform", platform != null);
            parameters.addValue("platform", platform == null ? "" : platform);
            parameters.addValue("filterKeyword", keyword != null);
            parameters.addValue("keyword", keyword == null ? "" : "%" + keyword + "%");
            parameters.addValue("filterCreatedFrom", createdFrom != null);
            parameters.addValue("createdFrom", java.sql.Timestamp.from(
                    createdFrom == null ? Instant.EPOCH : createdFrom));
            parameters.addValue("filterCreatedTo", createdTo != null);
            parameters.addValue("createdTo", java.sql.Timestamp.from(
                    createdTo == null ? Instant.EPOCH : createdTo));
            parameters.addValue("sort", sortKey(sort));
            return parameters;
        }
    }
}
