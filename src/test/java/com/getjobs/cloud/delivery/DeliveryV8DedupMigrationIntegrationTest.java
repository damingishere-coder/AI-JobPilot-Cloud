package com.getjobs.cloud.delivery;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V8 must fail closed when legacy rows already contain more than one task for
 * the same user and match. A schema migration must not guess which delivery
 * history is canonical or rewrite a successful/failed task into another state.
 */
@Testcontainers
class DeliveryV8DedupMigrationIntegrationTest {
    private static final String APP_USER = "jobpilot_app";
    private static final String APP_PASSWORD = "integration-app-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_jobpilot")
            .withUsername("jobpilot_owner")
            .withPassword("integration-owner-password");

    @Test
    void blocksLegacyDuplicateMatchTasksWithoutRewritingHistory() throws Exception {
        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE ai_jobpilot TO " + APP_USER);
        }

        Flyway toV7 = configured()
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("7"))
                .load();
        assertThat(toV7.migrate().migrationsExecuted).isEqualTo(7);

        UUID userId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        UUID preferenceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID succeededTaskId = UUID.randomUUID();
        UUID waitingTaskId = UUID.randomUUID();

        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            statement.execute("INSERT INTO app.users(id, email, password_hash) VALUES ('"
                    + userId + "', 'v8-duplicate@example.com', '$argon2id$test')");
            statement.execute("""
                    INSERT INTO app.resumes(
                        id, user_id, original_filename, storage_key, content_type, file_size,
                        sha256, upload_idempotency_key_hash, encryption_key_id, is_current, parse_status
                    ) VALUES (
                        '%s', '%s', 'resume.txt', 'objects/test', 'text/plain', 2,
                        repeat('a', 64), repeat('b', 64), 'v1', true, 'PARSED'
                    )
                    """.formatted(resumeId, userId));
            statement.execute("""
                    INSERT INTO app.job_preferences(id, user_id, version, target_titles)
                    VALUES ('%s', '%s', 1, '["Java Engineer"]'::jsonb)
                    """.formatted(preferenceId, userId));
            statement.execute("""
                    INSERT INTO app.job_posts(
                        id, user_id, platform, fingerprint, title, company_name, job_url,
                        source_captured_at, last_seen_at
                    ) VALUES (
                        '%s', '%s', 'BOSS', repeat('c', 64), 'Java Engineer', 'Example Co',
                        'https://www.zhipin.com/job_detail/v8.html', now(), now()
                    )
                    """.formatted(jobId, userId));
            statement.execute("""
                    INSERT INTO app.job_matches(
                        id, user_id, job_post_id, resume_id, preference_id, status,
                        decision, input_fingerprint, completed_at
                    ) VALUES (
                        '%s', '%s', '%s', '%s', '%s', 'SUCCEEDED',
                        'APPLY', repeat('d', 64), now()
                    )
                    """.formatted(matchId, userId, jobId, resumeId, preferenceId));
            statement.execute("""
                    INSERT INTO app.delivery_tasks(
                        id, user_id, job_post_id, job_match_id, status, finished_at,
                        confirmed_at, confirmed_by, idempotency_key_hash,
                        idempotency_payload_hash, created_at
                    ) VALUES (
                        '%s', '%s', '%s', '%s', 'SUCCEEDED', now() - interval '2 days',
                        now() - interval '2 days', '%s', repeat('e', 64), repeat('f', 64),
                        now() - interval '2 days'
                    )
                    """.formatted(succeededTaskId, userId, jobId, matchId, userId));
            statement.execute("""
                    INSERT INTO app.delivery_tasks(
                        id, user_id, job_post_id, job_match_id, status,
                        idempotency_key_hash, idempotency_payload_hash, created_at
                    ) VALUES (
                        '%s', '%s', '%s', '%s', 'PENDING_CONFIRMATION',
                        repeat('1a', 32), repeat('1b', 32), now() - interval '1 day'
                    )
                    """.formatted(waitingTaskId, userId, jobId, matchId));
        }

        assertThatThrownBy(() -> configured().load().migrate())
                .hasMessageContaining("duplicate delivery_tasks exist");

        try (Connection owner = ownerConnection(); var statement = owner.createStatement()) {
            assertThat(singleString(statement.executeQuery(
                    "SELECT status FROM app.delivery_tasks WHERE id='" + succeededTaskId + "'")))
                    .isEqualTo("SUCCEEDED");
            assertThat(singleString(statement.executeQuery(
                    "SELECT status FROM app.delivery_tasks WHERE id='" + waitingTaskId + "'")))
                    .isEqualTo("PENDING_CONFIRMATION");
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM app.delivery_tasks WHERE job_match_id='" + matchId + "'")))
                    .isEqualTo(2);
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM app.delivery_task_events WHERE delivery_task_id IN ('"
                            + succeededTaskId + "', '" + waitingTaskId + "')")))
                    .isZero();
            assertThat(singleLong(statement.executeQuery(
                    "SELECT count(*) FROM pg_indexes WHERE schemaname='app' "
                            + "AND indexname='delivery_tasks_user_match_unique'")))
                    .isZero();
        }
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration configured() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/postgresql")
                .defaultSchema("app")
                .schemas("app")
                .createSchemas(true)
                .cleanDisabled(true)
                .placeholders(Map.of("app_role", APP_USER));
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
    }

    private static long singleLong(ResultSet resultSet) throws SQLException {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private static String singleString(ResultSet resultSet) throws SQLException {
        try (resultSet) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
