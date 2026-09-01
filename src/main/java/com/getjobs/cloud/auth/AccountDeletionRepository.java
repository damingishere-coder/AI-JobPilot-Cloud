package com.getjobs.cloud.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("api")
public class AccountDeletionRepository {
    private final JdbcTemplate jdbc;

    public AccountDeletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DeletionRequest create(UUID userId, UUID requestId, String idempotencyKeyHash) {
        return jdbc.queryForObject(
                "SELECT * FROM app.request_account_deletion(CAST(? AS uuid), CAST(? AS uuid), CAST(? AS char(64)))",
                (resultSet, rowNumber) -> new DeletionRequest(
                        resultSet.getObject("deletion_request_id", UUID.class),
                        resultSet.getString("deletion_status"),
                        resultSet.getTimestamp("deletion_requested_at").toInstant()
                ),
                userId, requestId, idempotencyKeyHash
        );
    }

    public Optional<DeletionRequest> find(UUID userId, String idempotencyKeyHash) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM app.find_account_deletion(CAST(? AS uuid), CAST(? AS char(64)))",
                    (resultSet, rowNumber) -> new DeletionRequest(
                            resultSet.getObject("deletion_request_id", UUID.class),
                            resultSet.getString("deletion_status"),
                            resultSet.getTimestamp("deletion_requested_at").toInstant()
                    ),
                    userId, idempotencyKeyHash
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<ClaimedDeletion> claim(int leaseSeconds) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM app.claim_account_deletion(CAST(? AS integer))",
                    (resultSet, rowNumber) -> new ClaimedDeletion(
                            resultSet.getObject("deletion_request_id", UUID.class),
                            resultSet.getObject("deletion_user_id", UUID.class)
                    ),
                    leaseSeconds
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<String> storageKeys(UUID userId) {
        return jdbc.queryForList(
                """
                SELECT storage_key FROM app.resumes WHERE user_id=? AND storage_key IS NOT NULL
                UNION
                SELECT avatar_storage_key FROM app.user_profiles WHERE user_id=? AND avatar_storage_key IS NOT NULL
                """,
                String.class,
                userId, userId
        );
    }

    public boolean complete(UUID requestId, Instant backupExpiresAt) {
        Boolean result = jdbc.queryForObject(
                "SELECT app.complete_account_deletion(CAST(? AS uuid), CAST(? AS timestamptz))",
                Boolean.class,
                requestId, Timestamp.from(backupExpiresAt)
        );
        return result != null && result;
    }

    public void retry(UUID requestId, String errorCode, int maxAttempts) {
        jdbc.query(
                "SELECT app.retry_account_deletion(CAST(? AS uuid), CAST(? AS varchar), CAST(? AS integer))",
                resultSet -> { },
                requestId, errorCode, maxAttempts
        );
    }

    public record DeletionRequest(UUID id, String status, Instant requestedAt) {
    }

    public record ClaimedDeletion(UUID requestId, UUID userId) {
    }
}
