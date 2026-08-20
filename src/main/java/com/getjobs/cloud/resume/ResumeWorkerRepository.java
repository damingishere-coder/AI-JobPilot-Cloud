package com.getjobs.cloud.resume;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("worker")
public class ResumeWorkerRepository {
    private final JdbcTemplate jdbc;

    public ResumeWorkerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ParseJob> claimParse(int leaseSeconds) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM app.claim_resume_parse_job(?)",
                    this::mapParse,
                    leaseSeconds
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<PurgeJob> claimPurge(int leaseSeconds) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM app.claim_resume_purge_job(?)",
                    this::mapPurge,
                    leaseSeconds
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public boolean markParsed(
            ParseJob job,
            byte[] ciphertext,
            byte[] nonce,
            String keyId,
            Instant parsedAt
    ) {
        return jdbc.update(
                """
                UPDATE app.resumes
                SET parse_status='PARSED', parse_message='文本提取成功',
                    extracted_text_ciphertext=?, extracted_text_nonce=?, encryption_key_id=?,
                    parsed_at=?, parse_lease_token=NULL, parse_lease_until=NULL, version=version+1
                WHERE user_id=? AND id=? AND deleted_at IS NULL
                  AND parse_status='PARSING' AND parse_lease_token=?
                """,
                ciphertext, nonce, keyId, Timestamp.from(parsedAt),
                job.userId(), job.resumeId(), job.leaseToken()
        ) == 1;
    }

    public boolean markFailed(ParseJob job, String message) {
        return jdbc.update(
                """
                UPDATE app.resumes
                SET parse_status='FAILED', parse_message=?, parse_lease_token=NULL,
                    parse_lease_until=NULL, version=version+1
                WHERE user_id=? AND id=? AND deleted_at IS NULL AND parse_lease_token=?
                """,
                limitMessage(message), job.userId(), job.resumeId(), job.leaseToken()
        ) == 1;
    }

    public boolean reschedule(ParseJob job) {
        return jdbc.update(
                """
                UPDATE app.resumes
                SET parse_status='UPLOADED', parse_message='解析服务临时不可用，正在重试',
                    parse_lease_token=NULL, parse_lease_until=NULL, version=version+1
                WHERE user_id=? AND id=? AND deleted_at IS NULL AND parse_lease_token=?
                """,
                job.userId(), job.resumeId(), job.leaseToken()
        ) == 1;
    }

    public boolean markPurged(PurgeJob job, Instant purgedAt) {
        return jdbc.update(
                """
                UPDATE app.resumes
                SET purged_at=?, purge_lease_token=NULL, purge_lease_until=NULL, version=version+1
                WHERE user_id=? AND id=? AND deleted_at IS NOT NULL AND purged_at IS NULL
                  AND purge_lease_token=?
                """,
                Timestamp.from(purgedAt), job.userId(), job.resumeId(), job.leaseToken()
        ) == 1;
    }

    private ParseJob mapParse(ResultSet rs, int row) throws SQLException {
        return new ParseJob(
                rs.getObject("resume_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getString("object_storage_key"),
                rs.getString("object_content_type"),
                rs.getString("object_encryption_key_id"),
                rs.getInt("text_version"),
                rs.getObject("lease_token", UUID.class),
                rs.getInt("attempt_number")
        );
    }

    private PurgeJob mapPurge(ResultSet rs, int row) throws SQLException {
        return new PurgeJob(
                rs.getObject("resume_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getString("object_storage_key"),
                rs.getObject("lease_token", UUID.class)
        );
    }

    private String limitMessage(String message) {
        String safe = message == null || message.isBlank() ? "文本提取失败，请重新上传" : message.trim();
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }

    public record ParseJob(
            UUID resumeId,
            UUID userId,
            String storageKey,
            String contentType,
            String encryptionKeyId,
            int textVersion,
            UUID leaseToken,
            int attemptNumber
    ) {
    }

    public record PurgeJob(UUID resumeId, UUID userId, String storageKey, UUID leaseToken) {
    }
}
