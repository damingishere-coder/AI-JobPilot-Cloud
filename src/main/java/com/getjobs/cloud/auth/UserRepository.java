package com.getjobs.cloud.auth;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("api")
public class UserRepository {
    private static final String ACCOUNT_COLUMNS = """
            id, email::text AS email, password_hash, role, status,
            failed_login_count, locked_until, created_at
            """;
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByEmail(String normalizedEmail) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + ACCOUNT_COLUMNS + " FROM app.users WHERE email = ? AND deleted_at IS NULL",
                    this::mapAccount,
                    normalizedEmail
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<UserAccount> findById(UUID userId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + ACCOUNT_COLUMNS + " FROM app.users WHERE id = ? AND deleted_at IS NULL",
                    this::mapAccount,
                    userId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public void insertUser(UUID userId, String email, String passwordHash, UserStatus status) {
        jdbc.update(
                "INSERT INTO app.users(id, email, password_hash, status) VALUES (?, ?, ?, ?)",
                userId,
                email,
                passwordHash,
                status.name()
        );
    }

    public void setTenantContext(UUID userId) {
        jdbc.queryForObject(
                "SELECT set_config('app.current_user_id', ?, true)",
                String.class,
                userId.toString()
        );
    }

    public void insertDefaultProfile(UUID userId) {
        jdbc.update("INSERT INTO app.user_profiles(user_id) VALUES (?)", userId);
    }

    public Optional<UserProfile> findCurrentProfile(UUID userId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    """
                    SELECT user_id, display_name, city, timezone, locale
                    FROM app.user_profiles
                    WHERE user_id = ?
                    """,
                    (resultSet, rowNumber) -> new UserProfile(
                            resultSet.getObject("user_id", UUID.class),
                            resultSet.getString("display_name"),
                            resultSet.getString("city"),
                            resultSet.getString("timezone"),
                            resultSet.getString("locale")
                    ),
                    userId
            ));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public void markLoginSuccess(UUID userId, Instant now) {
        jdbc.update(
                """
                UPDATE app.users
                SET status = 'ACTIVE', failed_login_count = 0, locked_until = NULL, last_login_at = ?
                WHERE id = ?
                """,
                Timestamp.from(now),
                userId
        );
    }

    public void markLoginFailure(UUID userId, int failedCount, Instant lockedUntil) {
        jdbc.update(
                """
                UPDATE app.users
                SET failed_login_count = ?,
                    status = CASE WHEN ? THEN 'LOCKED' ELSE status END,
                    locked_until = ?
                WHERE id = ?
                """,
                failedCount,
                lockedUntil != null,
                lockedUntil == null ? null : Timestamp.from(lockedUntil),
                userId
        );
    }

    public void unlockExpired(UUID userId) {
        jdbc.update(
                "UPDATE app.users SET status = 'ACTIVE', locked_until = NULL WHERE id = ? AND status = 'LOCKED'",
                userId
        );
    }

    public void markEmailVerified(UUID userId, Instant verifiedAt) {
        jdbc.update(
                """
                UPDATE app.users
                SET status='ACTIVE', email_verified_at=?, failed_login_count=0, locked_until=NULL
                WHERE id=? AND status='PENDING' AND deleted_at IS NULL
                """,
                Timestamp.from(verifiedAt), userId
        );
    }

    public void updatePassword(UUID userId, String passwordHash) {
        jdbc.update(
                """
                UPDATE app.users
                SET password_hash=?, failed_login_count=0, locked_until=NULL,
                    status=CASE WHEN status='LOCKED' THEN 'ACTIVE' ELSE status END
                WHERE id=? AND deleted_at IS NULL
                """,
                passwordHash, userId
        );
    }

    private UserAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp lockedUntil = resultSet.getTimestamp("locked_until");
        return new UserAccount(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("email"),
                resultSet.getString("password_hash"),
                UserRole.valueOf(resultSet.getString("role")),
                UserStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("failed_login_count"),
                lockedUntil == null ? null : lockedUntil.toInstant(),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
