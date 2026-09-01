package com.getjobs.cloud.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("api")
public class AuthFlowRepository {
    private final JdbcTemplate jdbc;

    public AuthFlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String consumeInvite(String codeHash, UUID userId, String email, int maxUsers) {
        return jdbc.queryForObject(
                "SELECT app.consume_beta_invite(CAST(? AS char(64)), CAST(? AS uuid), CAST(? AS citext), CAST(? AS integer))",
                String.class,
                codeHash, userId, email, maxUsers
        );
    }

    public void recordConsent(UUID userId, String type, String version) {
        jdbc.query(
                "SELECT app.record_user_consent(CAST(? AS uuid), CAST(? AS varchar), CAST(? AS varchar))",
                resultSet -> { },
                userId, type, version
        );
    }

    public void createEmailToken(
            UUID userId, String purpose, String tokenHash, String email, Instant expiresAt
    ) {
        jdbc.query(
                "SELECT app.create_auth_email_token(CAST(? AS uuid), CAST(? AS varchar), CAST(? AS char(64)), CAST(? AS citext), CAST(? AS timestamptz))",
                resultSet -> { },
                userId, purpose, tokenHash, email, Timestamp.from(expiresAt)
        );
    }

    public Optional<EmailToken> consumeEmailToken(String tokenHash, String purpose) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM app.consume_auth_email_token(CAST(? AS char(64)), CAST(? AS varchar))",
                    (resultSet, rowNumber) -> new EmailToken(
                            resultSet.getObject("token_user_id", UUID.class),
                            resultSet.getString("token_email")
                    ),
                    tokenHash, purpose
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public record EmailToken(UUID userId, String email) {
    }
}
