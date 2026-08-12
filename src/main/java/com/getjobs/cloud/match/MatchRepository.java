package com.getjobs.cloud.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("api")
public class MatchRepository {
    private static final String COLUMNS = """
            m.id, m.user_id, m.job_post_id, m.resume_id, m.preference_id,
            m.status, m.score, m.decision, m.summary, m.strengths::text,
            m.risks::text, m.greeting, m.model_provider, m.model_name,
            m.prompt_version, m.input_tokens, m.output_tokens, m.duration_ms,
            m.error_code, m.error_message, m.attempt_count, m.created_at, m.completed_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MatchRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the latest match for a job (ordered by created_at DESC, id DESC).
     */
    public Optional<MatchRecord> findLatestByJob(UUID userId, UUID jobPostId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM app.job_matches m WHERE m.user_id=? AND m.job_post_id=?"
                            + " ORDER BY m.created_at DESC, m.id DESC LIMIT 1",
                    this::map,
                    userId, jobPostId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<MatchRecord> findById(UUID userId, UUID matchId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM app.job_matches m WHERE m.user_id=? AND m.id=?",
                    this::map,
                    userId, matchId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<MatchRecord> findByFingerprint(UUID userId, String fingerprint) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM app.job_matches m WHERE m.user_id=? AND m.input_fingerprint=?",
                    this::map,
                    userId, fingerprint
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Transactional
    public MatchRecord insert(
            UUID id,
            UUID userId,
            UUID jobPostId,
            UUID resumeId,
            UUID preferenceId,
            String status,
            String fingerprint
    ) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.job_matches AS m(
                    id, user_id, job_post_id, resume_id, preference_id, status, input_fingerprint
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS varchar), CAST(? AS char(64)))
                RETURNING %s
                """.formatted(COLUMNS),
                this::map,
                id, userId, jobPostId, resumeId, preferenceId, status, fingerprint
        );
    }

    /**
     * Force re-queue a FAILED match using the SECURITY DEFINER function.
     */
    public boolean forceRequeue(UUID userId, UUID matchId) {
        Boolean result = jdbc.queryForObject(
                "SELECT app.force_requeue_failed_match(?, ?)",
                Boolean.class,
                userId, matchId
        );
        return result != null && result;
    }

    /**
     * Returns the latest match per job post for a list of job IDs.
     * Uses DISTINCT ON to get only the most recent per job.
     */
    public List<MatchSummaryRecord> findLatestByJobIds(UUID userId, List<UUID> jobPostIds) {
        if (jobPostIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", jobPostIds.stream().map(id -> "?").toList());
        return jdbc.query(
                "SELECT DISTINCT ON (m.job_post_id) m.id, m.job_post_id, m.status, m.score, m.decision, "
                        + "m.greeting, m.completed_at "
                        + "FROM app.job_matches m WHERE m.user_id=? AND m.job_post_id IN (" + placeholders + ")"
                        + " ORDER BY m.job_post_id, m.created_at DESC, m.id DESC",
                this::mapSummary,
                params(userId, jobPostIds)
        );
    }

    private Object[] params(UUID userId, List<UUID> ids) {
        Object[] result = new Object[ids.size() + 1];
        result[0] = userId;
        for (int i = 0; i < ids.size(); i++) {
            result[i + 1] = ids.get(i);
        }
        return result;
    }

    private MatchRecord map(ResultSet rs, int row) throws SQLException {
        return new MatchRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("job_post_id", UUID.class),
                rs.getObject("resume_id", UUID.class),
                rs.getObject("preference_id", UUID.class),
                rs.getString("status"),
                getShort(rs, "score"),
                rs.getString("decision"),
                rs.getString("summary"),
                strings(rs.getString("strengths")),
                strings(rs.getString("risks")),
                rs.getString("greeting"),
                rs.getString("model_provider"),
                rs.getString("model_name"),
                rs.getString("prompt_version"),
                getInt(rs, "input_tokens"),
                getInt(rs, "output_tokens"),
                getInt(rs, "duration_ms"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getInt("attempt_count"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs, "completed_at")
        );
    }

    private MatchSummaryRecord mapSummary(ResultSet rs, int row) throws SQLException {
        return new MatchSummaryRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("job_post_id", UUID.class),
                rs.getString("status"),
                getShort(rs, "score"),
                rs.getString("decision"),
                rs.getString("greeting"),
                instant(rs, "completed_at")
        );
    }

    private Short getShort(ResultSet rs, String column) throws SQLException {
        short value = rs.getShort(column);
        return rs.wasNull() ? null : value;
    }

    private Integer getInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private List<String> strings(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    public record MatchRecord(
            UUID id,
            UUID userId,
            UUID jobPostId,
            UUID resumeId,
            UUID preferenceId,
            String status,
            Short score,
            String decision,
            String summary,
            List<String> strengths,
            List<String> risks,
            String greeting,
            String modelProvider,
            String modelName,
            String promptVersion,
            Integer inputTokens,
            Integer outputTokens,
            Integer durationMs,
            String errorCode,
            String errorMessage,
            int attemptCount,
            Instant createdAt,
            Instant completedAt
    ) {
    }

    public record MatchSummaryRecord(
            UUID id,
            UUID jobPostId,
            String status,
            Short score,
            String decision,
            String greeting,
            Instant completedAt
    ) {
    }
}
