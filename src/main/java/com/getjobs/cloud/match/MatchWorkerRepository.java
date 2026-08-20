package com.getjobs.cloud.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile({"api", "worker"})
public class MatchWorkerRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MatchWorkerRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Claims a single outbox entry for publishing to Redis Stream.
     * Stays PENDING until XADD confirmed.
     */
    public Optional<OutboxJob> claimOutbox(int leaseSeconds) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM app.claim_match_outbox_publish(?)",
                    this::mapOutbox,
                    leaseSeconds
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Confirms an outbox entry has been successfully published to Redis.
     */
    public boolean confirmOutbox(UUID outboxId, UUID leaseToken) {
        Boolean result = jdbc.queryForObject(
                "SELECT app.confirm_match_outbox_published(?, ?)",
                Boolean.class,
                outboxId, leaseToken
        );
        return result != null && result;
    }

    /**
     * Releases an outbox lease on Redis publish failure, scheduling retry with backoff.
     */
    public boolean releaseOutboxLease(UUID outboxId, UUID leaseToken, int retryDelaySeconds) {
        Boolean result = jdbc.queryForObject(
                "SELECT app.release_match_outbox_lease(?, ?, ?)",
                Boolean.class,
                outboxId, leaseToken, retryDelaySeconds
        );
        return result != null && result;
    }

    /**
     * Reads the quota reservation key persisted on a match, if any.
     * Historical matches created before quota integration stay NULL.
     */
    public Optional<String> findQuotaReservationKey(UUID userId, UUID matchId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT quota_reservation_key FROM app.job_matches WHERE user_id=? AND id=?",
                    String.class,
                    userId, matchId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Claims a match for AI processing (acquires lease, sets PROCESSING).
     * The database enforces attempt_count < maxAttempts before claiming.
     */
    public Optional<ProcessJob> claimMatch(UUID userId, UUID matchId, int leaseSeconds, int maxAttempts) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM app.claim_match_for_processing(?, ?, ?, ?)",
                    (rs, row) -> {
                        UUID leaseToken = rs.getObject("lease_token", UUID.class);
                        int attemptNumber = rs.getInt("attempt_number");
                        UUID returnedMatchId = rs.getObject("match_id", UUID.class);
                        UUID jobPostId = rs.getObject("job_post_id", UUID.class);
                        UUID resumeId = rs.getObject("resume_id", UUID.class);
                        UUID preferenceId = rs.getObject("preference_id", UUID.class);
                        if (returnedMatchId == null) return null;
                        return new ProcessJob(returnedMatchId, userId, jobPostId, resumeId,
                                preferenceId, leaseToken, attemptNumber);
                    },
                    userId, matchId, leaseSeconds, maxAttempts
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Completes a match with AI results.
     */
    public boolean completeMatch(
            UUID userId,
            UUID matchId,
            UUID leaseToken,
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
            String errorMessage
    ) {
        String strengthsJson = strengths == null || strengths.isEmpty() ? "[]" :
                toJsonStringArray(strengths);
        String risksJson = risks == null || risks.isEmpty() ? "[]" :
                toJsonStringArray(risks);

        Boolean result = jdbc.queryForObject(
                "SELECT app.complete_match(?, ?, ?, CAST(? AS varchar), CAST(? AS smallint), " +
                        "CAST(? AS varchar), CAST(? AS text), CAST(? AS jsonb), CAST(? AS jsonb), " +
                        "CAST(? AS varchar), CAST(? AS varchar), CAST(? AS varchar), " +
                        "CAST(? AS varchar), CAST(? AS integer), CAST(? AS integer), " +
                        "CAST(? AS integer), CAST(? AS varchar), CAST(? AS varchar))",
                Boolean.class,
                userId, matchId, leaseToken,
                status, score, decision, summary,
                strengthsJson, risksJson, greeting,
                modelProvider, modelName, promptVersion,
                inputTokens, outputTokens, durationMs,
                errorCode, errorMessage
        );
        return result != null && result;
    }

    /**
     * Resets a match to PENDING with backoff for retryable errors.
     */
    public boolean retryMatchLater(UUID userId, UUID matchId, UUID leaseToken, int retryDelaySeconds) {
        Boolean result = jdbc.queryForObject(
                "SELECT app.retry_match_later(?, ?, ?, ?)",
                Boolean.class,
                userId, matchId, leaseToken, retryDelaySeconds
        );
        return result != null && result;
    }

    /**
     * Releases a match lease (for graceful abort or stale lease cleanup).
     */
    public boolean releaseLease(UUID userId, UUID matchId, UUID leaseToken) {
        Boolean result = jdbc.queryForObject(
                "SELECT app.release_match_lease(?, ?, ?)",
                Boolean.class,
                userId, matchId, leaseToken
        );
        return result != null && result;
    }

    /**
     * DB fallback: claim any PENDING or expired-PROCESSING match across all users.
     * The database enforces attempt_count < maxAttempts before claiming.
     */
    public Optional<ProcessJob> claimOnePendingMatch(int leaseSeconds, int maxAttempts) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM app.claim_one_pending_match(?, ?)",
                    this::mapFallbackProcess,
                    leaseSeconds, maxAttempts
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Reads job post data for AI processing.
     */
    public JobData readJobData(UUID userId, UUID jobPostId) {
        return jdbc.queryForObject(
                """
                SELECT title, company_name, description FROM app.job_posts
                WHERE user_id=? AND id=?
                """,
                (rs, row) -> new JobData(
                        rs.getString("title"),
                        rs.getString("company_name"),
                        rs.getString("description")
                ),
                userId, jobPostId
        );
    }

    /**
     * Reads resume data including encrypted text for AI processing.
     */
    public ResumeData readResumeData(UUID userId, UUID resumeId) {
        return jdbc.queryForObject(
                """
                SELECT id, extracted_text_ciphertext, extracted_text_nonce,
                       encryption_key_id, text_version
                FROM app.resumes WHERE user_id=? AND id=?
                """,
                (rs, row) -> new ResumeData(
                        rs.getObject("id", UUID.class),
                        rs.getBytes("extracted_text_ciphertext"),
                        rs.getBytes("extracted_text_nonce"),
                        rs.getString("encryption_key_id"),
                        rs.getInt("text_version")
                ),
                userId, resumeId
        );
    }

    /**
     * Reads preference data for threshold-based decision.
     */
    public PreferenceData readPreferenceData(UUID userId, UUID preferenceId) {
        return jdbc.queryForObject(
                """
                SELECT version, target_titles::text, preferred_companies::text,
                       excluded_companies::text, excluded_keywords::text,
                       review_threshold, priority_apply_threshold, apply_threshold
                FROM app.job_preferences WHERE user_id=? AND id=?
                """,
                (rs, row) -> {
                    String titlesJson = rs.getString("target_titles");
                    String preferredJson = rs.getString("preferred_companies");
                    String excludedJson = rs.getString("excluded_companies");
                    String keywordsJson = rs.getString("excluded_keywords");
                    return new PreferenceData(
                            rs.getInt("version"),
                            titlesJson,
                            preferredJson,
                            excludedJson,
                            keywordsJson,
                            rs.getShort("review_threshold"),
                            rs.getShort("priority_apply_threshold"),
                            rs.getShort("apply_threshold")
                    );
                },
                userId, preferenceId
        );
    }

    private String toJsonStringArray(List<String> items) {
        try {
            return objectMapper.writeValueAsString(items == null || items.isEmpty() ? List.of() : items);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private OutboxJob mapOutbox(ResultSet rs, int row) throws SQLException {
        return new OutboxJob(
                rs.getObject("outbox_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getString("event"),
                rs.getObject("lease_token", UUID.class),
                rs.getInt("attempt_number")
        );
    }

    private ProcessJob mapFallbackProcess(ResultSet rs, int row) throws SQLException {
        UUID ownerUserId = rs.getObject("owner_user_id", UUID.class);
        UUID matchId = rs.getObject("match_id", UUID.class);
        UUID jobPostId = rs.getObject("job_post_id", UUID.class);
        UUID resumeId = rs.getObject("resume_id", UUID.class);
        UUID preferenceId = rs.getObject("preference_id", UUID.class);
        UUID leaseToken = rs.getObject("lease_token", UUID.class);
        int attemptNumber = rs.getInt("attempt_number");
        return new ProcessJob(matchId, ownerUserId, jobPostId, resumeId,
                preferenceId, leaseToken, attemptNumber);
    }

    record OutboxJob(
            UUID outboxId,
            UUID ownerUserId,
            UUID matchId,
            String eventType,
            UUID leaseToken,
            int attemptNumber
    ) {
    }

    record ProcessJob(
            UUID matchId,
            UUID ownerUserId,
            UUID jobPostId,
            UUID resumeId,
            UUID preferenceId,
            UUID leaseToken,
            int attemptNumber
    ) {
    }

    record JobData(String title, String companyName, String description) {
    }

    record ResumeData(
            UUID id,
            byte[] extractedTextCiphertext,
            byte[] extractedTextNonce,
            String encryptionKeyId,
            int textVersion
    ) {
    }

    record PreferenceData(
            int version,
            String targetTitlesJson,
            String preferredCompaniesJson,
            String excludedCompaniesJson,
            String excludedKeywordsJson,
            short reviewThreshold,
            short priorityApplyThreshold,
            short applyThreshold
    ) {
    }
}
